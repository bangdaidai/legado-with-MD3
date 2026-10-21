package io.legado.app.help.readaloud.cast

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.feature.reader.core.cast.CastMarkers
import io.legado.app.help.readaloud.analysis.ChapterSpeechBatchAnalyzer
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext

/**
 * 定稿写入：把分配表（`chapter_role_assignments`）那一份「哪句话归谁」写进说话人的真相表
 * （`chapter_speech_segments`）。
 *
 * 两张表的锚点不是一种东西——分配表按「章内第几个开引号」，分段表按「章内坐标」。桥不存进库里，
 * 而是**当场搭**：按当前设置重建这一章的带标记正文（与出计划时同一套 splitPolicy），量出每句台词的
 * 范围，再按区间重叠对上分段，过一道同源核对（那几段还得量得现在这份正文）。分配表因此不需要存段的
 * id，也就不动表结构。
 *
 * 两个入口，写的是同一套列：
 * - [finalize]：用户在正文胶囊点「确认／新建」的当下。库里已有这一章的分段时当场就写进去，
 *   分镜页立刻是那一个角色，不用等本章重新准备。
 * - [replay]：每次出计划时（[io.legado.app.domain.usecase.PrepareChapterSpeechPlanUseCase]）整章回放
 *   一遍。它是兜底，负责 [finalize] 那一刻**没有可写目标**的情形：这一章还没分析过（库里没有分段）、
 *   或当场读正文失败。
 * 判据都是「这一句已经是这个角色的定稿就什么都不写」，所以两边重复跑不会把 `updatedAt` 一直往前推。
 *
 * 为什么要这座桥：播出时"谁说话"由覆盖层按标记现场认领（历史做法），等于让播放队列自己又判了一遍
 * 归属，界面与耳朵各拿一份真相。写进 segments 之后，朗读计划、分镜页、正文胶囊都读同一段结论，
 * 而 `userLocked = true` 让重跑分析跳过这一段——用户手工分的句子不会被 AI 下一次改回去
 * （现成的六处跳过判断分布在 `RefineSpeechWithAiUseCase`、`ResolveLocalSpeakersUseCase`、
 * `LocalCharacterSpeakerResolver`，缺的只是写入方，就是这里）。
 *
 * 冲突口径写死：**后一次用户动作赢**。分配覆盖已有归属时照写，并把被顶掉的那一个记进日志；
 * 要回 AI 原来的判断，取消分配后重新分析这一章。
 */
object CastSegmentFinalizer {

    private val chapterSpeechGateway: ChapterSpeechGateway by lazy {
        GlobalContext.get().get<ChapterSpeechGateway>()
    }

    /**
     * 回放到这一章刚算好的那份 [segments] 上，返回改完的那一份（调用方直接拿它选音，
     * 不用为了看到定稿再读一次库）。
     *
     * 一条分配都没有（绝大多数书都是这样）时原样返回，不碰库也不碰 [CastSpeechOverlay]。
     * 正文里没有角色标记时同样原样返回：标记只由「显示角色」注入，关掉它就是没有按句的分配可读，
     * 这与历史上覆盖层的口径一致，不是这次新加的门槛。
     */
    suspend fun replay(
        bookUrl: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
        segments: List<ChapterSpeechSegment>,
    ): List<ChapterSpeechSegment> {
        if (segments.isEmpty() || paragraphs.isEmpty()) return segments
        val rows = appDb.chapterRoleAssignmentDao.getForChapter(bookUrl, chapterIndex)
            .filter { it.characterId.isNotBlank() && it.characterName.isNotBlank() }
            .associateBy { it.quoteOrdinal }
        if (rows.isEmpty()) return segments
        // 手里这几段量不到这份正文（正文换过了）时不写：坐标对不上，写了就是把归属钉在错的那句话上。
        // 换了划分方式不算对不上——旧粒度的那几段仍落在同一页正文的同一些字上。
        if (!SpeechIdentity.alignsWithParagraphs(paragraphs, segments)) return segments
        val spans = CastSpeechOverlay.spansOf(paragraphs).filter { it.ordinal in rows }
        if (spans.isEmpty()) return segments
        var result = segments
        var overwritten = 0
        val names = LinkedHashSet<String>()
        spans.forEach { span ->
            val row = rows[span.ordinal] ?: return@forEach
            val pending = pendingOf(result, paragraphs, span, row.characterId, row.characterName)
            if (pending.toWrite.isEmpty()) return@forEach
            pending.pushedOutOf.forEach { segment ->
                overwritten++
                names += segment.characterName
            }
            pending.toWrite.forEach { segment ->
                chapterSpeechGateway.setSegmentAttribution(
                    segmentId = segment.id,
                    roleType = pending.roleType,
                    characterId = row.characterId,
                    characterName = row.characterName,
                    userLocked = true,
                )
                result = result.map {
                    if (it.id == segment.id) {
                        it.copy(
                            roleType = pending.roleType,
                            characterId = row.characterId,
                            characterName = row.characterName,
                            userLocked = true,
                        )
                    } else {
                        it
                    }
                }
            }
        }
        if (overwritten > 0) {
            // 顶掉别人的结论必须留话：不记就查不出"这句明明是张三念的，怎么听着是旁白"
            // 到底是分配没写进去，还是别的句子也分配给了同一个角色。
            AppLog.put(
                "定稿回放: 第${chapterIndex + 1}章有 $overwritten 段原记「${names.joinToString("、")}」，" +
                    "按分配表改给最新那一位（要回原来的判断，取消分配后重新分析这一章）"
            )
        }
        return result
    }

    /**
     * 用户在正文胶囊点「确认／新建」的当下：把这一句的归属立刻写进库里那几段并锁住。
     *
     * 不必等本章重新准备——分镜页读的就是那一份分段，写完这一行显示的就是那一个角色。
     * 判据与 [replay] 同一条，所以随后出计划时是空转（一行都不重刷）。
     *
     * 量不到坐标（这一章还没分析过，或正文换过了、库里那几段量不到现在这一页）时**什么都不做**：
     * 分配行照样落库，出计划那一次由 [replay] 补上，所以这里对不上不影响用户这一次点击成立。
     */
    suspend fun finalize(
        bookUrl: String,
        chapterIndex: Int,
        quoteOrdinal: Int,
        characterId: String,
        characterName: String,
    ) {
        val bridge = bridgeOf(bookUrl, chapterIndex)
        val span = bridge?.spans?.firstOrNull { it.ordinal == quoteOrdinal }
        if (bridge == null || span == null) {
            AppLog.put(
                "定稿: 第${chapterIndex + 1}章这一句暂时对不上库里那份分段" +
                    "（这一章还没分析过，或正文换过了），下次出计划时补"
            )
            return
        }
        val pending = pendingOf(
            bridge.segments, bridge.paragraphs, span, characterId, characterName,
        )
        if (pending.toWrite.isEmpty()) return
        pending.toWrite.forEach { segment ->
            chapterSpeechGateway.setSegmentAttribution(
                segmentId = segment.id,
                roleType = pending.roleType,
                characterId = characterId,
                characterName = characterName,
                userLocked = true,
            )
        }
        val pushed = pending.pushedOutOf.map { it.characterName }.distinct()
            .joinToString("、") { "「$it」" }
            .let { if (it.isEmpty()) "" else "，顶掉原记$it" }
        AppLog.put(
            "定稿: 第${chapterIndex + 1}章第 ${quoteOrdinal + 1} 句改给「$characterName」，" +
                "写进 ${pending.toWrite.size} 段并锁住$pushed" +
                "（要回原来的判断，取消分配后重新分析这一章）"
        )
    }

    /**
     * 一句台词对应到分段之后的结果：写成什么角色类型、哪几段真的要写、哪几段是被顶掉的那个结论。
     *
     * 「已经是这个角色的定稿」的段要从 [Pending.toWrite] 里滤掉——回放每次出计划都跑，
     * 逐行重刷会把 `updatedAt` 一直往前推，分镜页的"本章何时更新"就再也不能看。
     */
    private fun pendingOf(
        segments: List<ChapterSpeechSegment>,
        paragraphs: List<CanonicalSpeechParagraph>,
        span: CastSpeechOverlay.Span,
        characterId: String,
        characterName: String,
    ): Pending {
        val roleType = if (span.isThoughtQuote(paragraphs)) {
            SpeechRoleType.Thought
        } else {
            SpeechRoleType.Character
        }
        val hit = targetsOf(segments, span)
        val toWrite = hit.filterNot {
            it.characterId == characterId && it.characterName == characterName &&
                it.roleType == roleType && it.userLocked
        }
        return Pending(
            roleType = roleType,
            toWrite = toWrite,
            pushedOutOf = toWrite.filter {
                it.characterName.isNotBlank() && it.characterId != characterId
            },
        )
    }

    private class Pending(
        val roleType: SpeechRoleType,
        val toWrite: List<ChapterSpeechSegment>,
        val pushedOutOf: List<ChapterSpeechSegment>,
    )

    /**
     * 取消一句分配时放开它的定稿：解锁 + 回落旁白。
     *
     * **调用时机必须在删分配行之前**——这一句的标记就是那一行注入的，行没了正文里就量不到这句的范围。
     * 库里那一份量不到现在这份正文（坐标对不上）时，放开不了也只是那一行留在那一份旧结果里：
     * 播出读的是这一章那份结果，而写新分析时旧的会被删掉，不会留下一个永久锁死的孤儿段。
     */
    suspend fun release(bookUrl: String, chapterIndex: Int, quoteOrdinal: Int) {
        val bridge = bridgeOf(bookUrl, chapterIndex) ?: return
        bridge.spans.filter { it.ordinal == quoteOrdinal }.forEach { span ->
            targetsOf(bridge.segments, span).forEach { unlock(it.id) }
        }
    }

    /** 删掉整章分配（目录长按菜单「删除分配」）：本章被定稿过的段一并放开。 */
    suspend fun releaseChapter(bookUrl: String, chapterIndex: Int) {
        val rows = appDb.chapterRoleAssignmentDao.getForChapter(bookUrl, chapterIndex)
            .mapTo(hashSetOf()) { it.quoteOrdinal }
        if (rows.isEmpty()) return
        val bridge = bridgeOf(bookUrl, chapterIndex) ?: return
        bridge.spans.filter { it.ordinal in rows }
            .flatMap { targetsOf(bridge.segments, it) }
            .map { it.id }
            .distinct()
            .forEach { unlock(it.id) }
    }

    /**
     * 事后重算一次这座桥：重建带标记正文 + 与库里那份同源核对 + 量出每句台词的范围。
     *
     * 与 [replay] 的区别只在段落是谁给的：回放用调用方手里那份，用户当场点「确认／取消」时手上没有
     * 那份正文，只能按当前设置重建一遍。核对走 [SpeechIdentity.alignsWithParagraphs]——只看那几段
     * 还量得到现在这份正文，**不比内容哈希**：换了划分方式之后重建的段落和库里旧粒度的那份哈希必然
     * 不等，可那几段的章内坐标照样对得上，桥照搭；真会判不同源的只有正文换过（重新下载、替换规则、
     * 开关「显示角色」），那种情况对不上就当桥不存在，等 [replay] 兜底。
     */
    private suspend fun bridgeOf(
        bookUrl: String,
        chapterIndex: Int,
    ): Bridge? = runCatching {
        val policy = ChapterSpeechBatchAnalyzer.splitPolicy()
        val stored = chapterSpeechGateway.getChapterSegments(bookUrl, chapterIndex)
        if (stored.isEmpty()) return@runCatching null
        val paragraphs = ChapterSpeechBatchAnalyzer.paragraphsOf(bookUrl, chapterIndex, policy)
            ?: return@runCatching null
        if (!SpeechIdentity.alignsWithParagraphs(paragraphs, stored)) return@runCatching null
        Bridge(stored, CastSpeechOverlay.spansOf(paragraphs), paragraphs)
    }.onFailure {
        if (it is CancellationException) throw it
    }.getOrNull()

    /** 一座桥的三件东西：库里那几段、量出来的每句台词范围、量它用的那份带标记正文。 */
    private class Bridge(
        val segments: List<ChapterSpeechSegment>,
        val spans: List<CastSpeechOverlay.Span>,
        val paragraphs: List<CanonicalSpeechParagraph>,
    )

    private suspend fun unlock(segmentId: String) {
        chapterSpeechGateway.setSegmentAttribution(
            segmentId = segmentId,
            roleType = SpeechRoleType.Narrator,
            characterId = null,
            characterName = "",
            userLocked = false,
        )
    }

    /**
     * 这段台词覆盖到的分段。
     *
     * 按「有交集」取，不是只取开引号那一段：一句台词常被标点切成好几段（按符号划分时尤其明显），
     * 只定稿第一段的话，后半句还是 AI 那份判断，听着就是同一句话说一半换一个人的声音。
     * 整段划分下一句台词和它前后的叙述本来就在同一段里，于是整段一起定稿——那是划分方式本身的粒度，
     * 分析链自己给归属时也是这个粒度，不是这里额外造成的。
     */
    private fun targetsOf(
        segments: List<ChapterSpeechSegment>,
        span: CastSpeechOverlay.Span,
    ): List<ChapterSpeechSegment> = segments.filter {
        it.chapterPosition < span.end && span.start < it.chapterPosition + it.text.length
    }

    /**
     * 这一句是**单引号**台词（心声那一类）吗。
     *
     * 判据就是范围起点那个字符：[CastSpeechOverlay.Span] 的 start 取的就是开引号。
     * 走正文而不是 [CastAssignmentStore.thoughtOrdinals]，是因为回放每次出计划都要跑，
     * 为此再读一遍正文不值当。当场定稿那一路也不需要：桥里本来就带着量坐标用的那份段落，
     * 两边共用这一个判据，才不会「当场写成台词、回放又改成心声」。
     */
    private fun CastSpeechOverlay.Span.isThoughtQuote(
        paragraphs: List<CanonicalSpeechParagraph>,
    ): Boolean {
        val paragraph = paragraphs.lastOrNull { it.chapterPosition <= start } ?: return false
        val offset = start - paragraph.chapterPosition
        val ch = paragraph.text.getOrNull(offset) ?: return false
        return ch in CastMarkers.SingleQuoteOpens
    }
}
