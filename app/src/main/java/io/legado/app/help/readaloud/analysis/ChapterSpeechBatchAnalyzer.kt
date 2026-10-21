package io.legado.app.help.readaloud.analysis

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ContentSplitPolicies
import io.legado.app.domain.model.readaloud.ContentSplitPolicy
import io.legado.app.domain.model.readaloud.SpeechAnalysisMode
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.settings.ReadAloudContentSplitMode
import io.legado.app.domain.usecase.ResolveEffectiveSpeechPlanUseCase
import io.legado.app.feature.reader.core.readaloud.ReaderReadAloudChapter
import io.legado.app.feature.reader.core.source.ReaderChapterSourceParser
import io.legado.app.feature.reader.platform.AndroidReaderHtmlSemanticTextResolver
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.readaloud.cast.CastAssignmentStore
import io.legado.app.ui.config.readConfig.ReadConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

/**
 * 批量把章跑成分析：朗读服务「顺带往后分析几章」与分镜结果页的显式动作共用这一个实现。
 *
 * 为什么要有它：跑哪几章这件事原先只有「AI 分配角色」弹窗会问（现场挑起止章，跑完自己记账
 * 好续跑）。那个弹窗退役后，范围改成朗读侧的一档（顺带往后分析几章），执行入口只剩两处——
 * 朗读时顺带跑、结果页点一下立刻跑。两处都必须与实时朗读**同一份划分口径、同一个分析入口**，
 * 否则新补出来的那一份和耳朵里听的那一份对不上——补分析跑的是没跑过的章，跑的口径必须一致。
 *
 * 段落重建走预合成同一条链（ContentProcessor → ReaderChapterSourceParser →
 * [ReaderReadAloudChapter] → canonicalSpeechParagraphs），落库走
 * [ResolveEffectiveSpeechPlanUseCase]（"这一章该怎么念"的唯一入口），不另开第二条分析链。
 *
 * 「续跑」在这里是免费的：库里已经有结果的章直接跳过，一次 AI 请求都不发，所以重跑
 * 一整段范围只会补上没跑成的那几章。判据不看当前设置（改划分方式 / 模型不算没跑过），
 * 只看那一行结果还在不在、还量不量得到现在这份正文。失败章重试走结果页（那里会先删掉那一章的旧结论再跑）。
 */
object ChapterSpeechBatchAnalyzer {

    /** 一章跑完的结果，界面按它汇总"成功几章、跳过几章、几章没跑成"。 */
    enum class ChapterOutcome {
        /** 这一轮真的跑了分析 */
        Analyzed,

        /** 库里已有这一章的结果，且还量得到现在的正文，一个 token 都没再花 */
        AlreadyAnalyzed,

        /** 正文还没下载到本地，没法重建段落 */
        ContentMissing,

        /** 跑了但失败（AI 请求失败 / 分析链出错），原因在 [Outcome.error] */
        Failed,
    }

    data class Outcome(
        val chapterIndex: Int,
        val title: String,
        val result: ChapterOutcome,
        val error: String? = null,
    )

    private val resolvePlan: ResolveEffectiveSpeechPlanUseCase by lazy {
        GlobalContext.get().get<ResolveEffectiveSpeechPlanUseCase>()
    }
    private val chapterSpeechGateway: ChapterSpeechGateway by lazy {
        GlobalContext.get().get<ChapterSpeechGateway>()
    }
    private val otherSettingsGateway: OtherSettingsGateway by lazy {
        GlobalContext.get().get<OtherSettingsGateway>()
    }

    /**
     * 与朗读服务、分镜页同一份划分口径。
     *
     * 三处都从这里取：划分方式跟着「多角色朗读」走，改了设置三处一起改，不会出现
     * "补分析按旧粒度切、朗读按新粒度切"那种两边对不上的分段。
     */
    fun contentSplitMode(): ReadAloudContentSplitMode = ContentSplitPolicies.resolve(
        ReadAloudContentSplitMode.fromStorage(ReadConfig.contentSplitMode),
        ReadConfig.useMultiSpeaker,
    )

    fun splitPolicy(): ContentSplitPolicy =
        ContentSplitPolicies.forMode(contentSplitMode(), ReadConfig.contentSplitSymbols)

    /**
     * 这批值不值得跑：多角色朗读没开、或分析模式是纯规则时，补分析没有任何意义。
     *
     * 纯规则模式下"分析"就是本地切分，翻到那一章时当场就会算出来，没必要提前跑一遍还占着
     * 预合成的时间。调用方据此决定是静默跳过（朗读服务）还是把话说给用户听（结果页）。
     */
    fun isWorthRunning(): Boolean =
        ReadConfig.useMultiSpeaker &&
            SpeechAnalysisMode.fromStorage(ReadConfig.speechAnalysisMode) != SpeechAnalysisMode.Rule

    /**
     * 跑一批章：逐章交给 [ResolveEffectiveSpeechPlanUseCase]，一章跑完就回调 [onChapter]。
     *
     * 逐章回调而不是一次性返回，界面才能够在跑几十章的途中把进度念给用户听，并让「停止」
     * 在章与章之间生效（[ensureActive]）。跑批中途退页只放弃后面没开的章，在飞的那一章
     * 由分析链自己的不可取消上下文跑完落库。
     *
     * [resetCache] = true 时先删掉这一章的旧结论再跑：**库里那一份是这一章的结果**，
     * 不删的话这一条链会直接照用（连失败状态的那一份也照用），「重新分析」就白点、
     * 失败章也永远重试不了。
     *
     * [skipAnalyzed] = true 是「续跑免费」的落点：已经有结果的章直接跳过，一个 token 都不花，
     * 所以重跑一整段范围只会补上没跑成的那几章。
     */
    suspend fun analyze(
        bookUrl: String,
        chapterIndexes: List<Int>,
        source: String,
        resetCache: Boolean = false,
        skipAnalyzed: Boolean = true,
        onChapter: suspend (Outcome) -> Unit = {},
    ): List<Outcome> {
        if (chapterIndexes.isEmpty()) return emptyList()
        val book = withContext(Dispatchers.IO) { appDb.bookDao.getBook(bookUrl) }
            ?: return emptyList()
        val policy = splitPolicy()
        val outcomes = mutableListOf<Outcome>()
        chapterIndexes.forEach { chapterIndex ->
            currentCoroutineContext().ensureActive()
            val title = withContext(Dispatchers.IO) {
                appDb.bookChapterDao.getChapter(bookUrl, chapterIndex)?.title.orEmpty()
            }
            val outcome = runCatching {
                if (resetCache) {
                    withContext(Dispatchers.IO) { chapterSpeechGateway.deleteChapter(bookUrl, chapterIndex) }
                    resolvePlan.clearAiCooldown(bookUrl)
                }
                val paragraphs = paragraphsOf(book, chapterIndex, policy)
                    ?: return@runCatching Outcome(chapterIndex, title, ChapterOutcome.ContentMissing)
                // 跳过与否要拿本轮这份段落去核对，所以段落先重建：那是读本地正文 + 解析，
                // 不发请求也不花钱，比先判版本再重建多读一次正文值当
                if (!resetCache && skipAnalyzed && isAnalyzed(bookUrl, chapterIndex, paragraphs)) {
                    return@runCatching Outcome(chapterIndex, title, ChapterOutcome.AlreadyAnalyzed)
                }
                resolvePlan(
                    bookUrl = bookUrl,
                    chapterIndex = chapterIndex,
                    paragraphs = paragraphs,
                    splitPolicy = policy,
                    source = source,
                )
                Outcome(chapterIndex, title, ChapterOutcome.Analyzed)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                Outcome(
                    chapterIndex = chapterIndex,
                    title = title,
                    result = ChapterOutcome.Failed,
                    error = error.localizedMessage ?: error.javaClass.simpleName,
                )
            }
            outcomes += outcome
            onChapter(outcome)
        }
        return outcomes
    }

    /**
     * 这一章存下来的结果会不会被朗读直接拿去做计划：有分段，而且那些分段还量在当前这份正文上。
     *
     * 这是"续跑免费"的落点，判据与 [io.legado.app.domain.usecase.AnalyzeChapterSpeechUseCase]
     * 同一套：**不再比版本号**——划分方式 / 分析模式 / 模型 / 提示词 / 归因口径变了都不算
     * 「没分析过」，跑批因此不会替用户重烧已经跑好的章（用户口径：没点重新分析就没必要）。
     * 只有正文换过（重新下载、改替换规则、开关「显示角色」）那份对不上现在的坐标，才真的重跑。
     */
    private suspend fun isAnalyzed(
        bookUrl: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
    ): Boolean = runCatching {
        val stored = chapterSpeechGateway.getChapterSegments(bookUrl, chapterIndex)
        stored.isNotEmpty() && SpeechIdentity.alignsWithParagraphs(paragraphs, stored)
    }.getOrDefault(false)

    /**
     * 与预合成同一条段落重建链。
     *
     * 页边界这里拿不到（这一章没在屏幕上排过版），所以按整段视图重建——与预合成对往后各章
     * 做的事一致。正文没下载时 [BookHelp.getContent] 返回 null，调用方按「这一章没法分析」处理，
     * 不去触发下载：下载是朗读本身的事，补分析不该替它排队。
     */
    suspend fun paragraphsOf(
        bookUrl: String,
        chapterIndex: Int,
        policy: ContentSplitPolicy,
    ): List<CanonicalSpeechParagraph>? {
        val book = withContext(Dispatchers.IO) { appDb.bookDao.getBook(bookUrl) } ?: return null
        return paragraphsOf(book, chapterIndex, policy)
    }

    suspend fun paragraphsOf(
        book: Book,
        chapterIndex: Int,
        policy: ContentSplitPolicy,
    ): List<CanonicalSpeechParagraph>? {
        val chapter = withContext(Dispatchers.IO) {
            appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
        } ?: return null
        val content = withContext(Dispatchers.IO) { BookHelp.getContent(book, chapter) } ?: return null
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val displayTitle = chapter.getDisplayTitle(
            contentProcessor.getTitleReplaceRules(),
            book.getUseReplaceRule(otherSettingsGateway.currentSettings.replaceEnableDefault),
            chineseConverterType = ReadConfig.chineseConverterType,
        )
        val processedContent = contentProcessor.getContent(
            book,
            chapter,
            content,
            includeTitle = false,
        )
        val source = ReaderChapterSourceParser.parse(
            chapterIndex = chapterIndex,
            title = displayTitle,
            paragraphs = processedContent.textList,
            includeTitle = false,
            adaptSpecialStyle = ReadConfig.adaptSpecialStyle,
            htmlSemanticTextResolver = AndroidReaderHtmlSemanticTextResolver,
            castLabels = if (ReadConfig.multiRoleCast) {
                CastAssignmentStore.labelsForChapter(book.bookUrl, chapterIndex)
            } else {
                null
            },
        )
        return withContext(Dispatchers.Default) {
            ReaderReadAloudChapter.create(
                chapterIndex = chapterIndex,
                title = displayTitle,
                semanticContent = source.semanticContent,
                // 补分析不看页边界：这一章没在屏幕上排过版，按整段视图重建
                pageStarts = listOf(0),
                contentSplitMode = policy.mode,
            ).canonicalSpeechParagraphs(
                splitByPage = false,
                policy = policy,
            )
        }
    }
}
