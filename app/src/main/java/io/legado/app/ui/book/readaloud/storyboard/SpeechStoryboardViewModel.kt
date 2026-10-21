package io.legado.app.ui.book.readaloud.storyboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ChapterSpeechSegments
import io.legado.app.domain.model.readaloud.ContentSplitPolicies
import io.legado.app.domain.model.readaloud.ContentSplitPolicy
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechAnalysisMode
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechPlanItem
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.settings.ReadAloudContentSplitMode
import io.legado.app.domain.usecase.RefineSpeechWithAiUseCase
import io.legado.app.domain.usecase.ResolveEffectiveSpeechPlanUseCase
import io.legado.app.domain.usecase.ruleResolverVersion
import io.legado.app.feature.reader.core.readaloud.ReaderReadAloudChapter
import io.legado.app.help.readaloud.playback.VoicePreviewSynthesizer
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudSessionStore
import io.legado.app.model.ReadBook
import io.legado.app.ui.config.readConfig.ReadConfig
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import splitties.init.appCtx

/**
 * 分镜结果页：先按章列出分析过的章节，点进去按场景分组看这一章的分段、说话人和音色。
 *
 * 计划由 [ResolveEffectiveSpeechPlanUseCase] 单独算，与朗读服务走的是同一个用例——分镜页
 * 过去只截取了「分段 + 选音」两步，跳过了分配表覆盖，于是显示的音色是覆盖**之前**的，
 * 实际发声是覆盖**之后**的；整段/整页划分下覆盖层还会再切一刀，一行分镜根本不对应任何
 * 一条实际发声的单元。
 *
 * 库里已经有分段的章节一律读缓存再套分配表覆盖，只有"正在读且还没分析过"的那一章才现跑完整
 * 分析链——分段按朗读服务的同一划分口径从新引擎章节输入重建，单独去数据库捞正文再切一次只会和
 * 朗读时对不上。套覆盖前还要比一次正文哈希：段落和分析当时不是同一份就跳过，宁可少显示一层，
 * 也不能把胶囊按错坐标安到别人的话上（见 [cachedChapterPlan]）。
 *
 * 场景分组是朗读分析之外按需补跑的一层（[RefineSpeechWithAiUseCase.assignScenes]）：
 * 只有 AI 分析模式的分镜页会触发，成功才写回库；失败保持扁平列表，不影响朗读。
 * 这一层的取消口径与说话人分析主链一致——**离开页面不掐 AI**：看一眼没等完就退出去别的地方，
 * 请求照样跑完、结果照样落库，下次进这章直接命中；只有该章被重新分析或删除才作废。
 */
class SpeechStoryboardViewModel(
    private val bookUrl: String,
    private val resolveEffectiveSpeechPlan: ResolveEffectiveSpeechPlanUseCase,
    private val refineSpeechWithAi: RefineSpeechWithAiUseCase,
    private val chapterSpeechGateway: ChapterSpeechGateway,
    private val bookKnowledgeGateway: BookKnowledgeGateway,
    private val previewSynthesizer: VoicePreviewSynthesizer,
    private val sessionStore: ReadAloudSessionStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpeechStoryboardUiState(bookUrl = bookUrl))
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SpeechStoryboardEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var sceneJob: Job? = null
    private var previewJob: Job? = null
    private var deleteJob: Job? = null

    /** 详情页当前加载的完整计划，试听按 id 找回音色；离开详情页清空 */
    private var currentPlan: List<SpeechPlanItem> = emptyList()

    /** 本次打开页面内尝试过场景拆分的章节，失败的不重打 AI */
    private val sceneAttemptedChapters = hashSetOf<Int>()

    /**
     * 「这一章的分析在本页内被作废」的代次：重新分析、删除章节各 +1。
     *
     * 场景拆分现在会跨页面跑完再落库，回写前比对代次，避免拿离开页面那一刻的旧分段
     * 盖掉用户刚重跑出来的那份分析。
     */
    private var sceneGeneration = 0

    /**
     * 场景拆分串行锁。
     *
     * 取消不再掐请求后，"快速连点好几章"会变成多个在飞 AI 请求并发打同一个模型，
     * 只会互相挤到超时、全部白烧（主链当年就是靠同款锁治的）。这里让它们在锁上排队，
     * 一轮一轮跑完落库。
     */
    private val sceneMutex = Mutex()

    init {
        loadChapters("进入分镜页")
        observePlaybackPosition()
    }

    /**
     * 跟着朗读服务的位置高亮"当前正在念的这一条"，并把它的**实际发声音色**（运行时结算值）
     * 一并带进界面。
     *
     * 只有细节页需要：章内位置对不上正在看的那一章就没有意义。这里只发章内位置与当前句的
     * 结算音色、不发其它状态，避免逐词进度把整棵分镜树带着一起重组。
     */
    private fun observePlaybackPosition() {
        viewModelScope.launch {
            sessionStore.state.collect { session ->
                val playback = session.playback
                _uiState.update {
                    val sameChapter = playback.chapterIndex == it.selectedChapterIndex
                    it.copy(
                        playingChapterPosition = if (sameChapter) {
                            playback.chapterPosition
                        } else {
                            -1
                        },
                        // 计划音色 → 结算音色：音色路由/引擎拒绝发生在发声瞬间，只有正在播
                        // 的那句知道真实值（displayVoiceLabel = 结算值，无则回落计划值）。
                        playingVoiceLabel = if (sameChapter) playback.displayVoiceLabel else "",
                    )
                }
            }
        }
    }

    override fun onCleared() {
        // 取消带原因：退页不再掐 AI（主链与场景层都跑完落库），这一刀只放弃把结果回投界面，
        // AI 日志里留下的取消条目说的是"谁放弃了结果"，不是"谁杀了请求"
        loadJob?.cancel(CancellationException("退出分镜页，本轮加载不再回投界面（在飞的 AI 仍会跑完落库）"))
        sceneJob?.cancel(CancellationException("退出分镜页，场景拆分不再回投界面（在飞的 AI 仍会跑完落库）"))
        previewJob?.cancel()
        previewJob = null
        super.onCleared()
    }

    fun onIntent(intent: SpeechStoryboardIntent) {
        val selected = _uiState.value.selectedChapterIndex
        when (intent) {
            SpeechStoryboardIntent.Refresh ->
                if (selected == null) loadChapters("刷新章节列表") else loadChapter(selected, reanalyze = false)

            SpeechStoryboardIntent.Reanalyze ->
                selected?.let { loadChapter(it, reanalyze = true) }

            is SpeechStoryboardIntent.OpenChapter -> loadChapter(intent.chapterIndex, reanalyze = false)

            SpeechStoryboardIntent.BackToChapters -> {
                stopPreview()
                loadChapters("返回章节列表")
            }

            is SpeechStoryboardIntent.PreviewSegment -> previewSegment(intent.itemId)

            is SpeechStoryboardIntent.ToggleSegmentLock -> toggleSegmentLock(intent.itemId)

            SpeechStoryboardIntent.StopPreview -> stopPreview()

            SpeechStoryboardIntent.PreviewFinished -> _uiState.update {
                it.copy(previewingItemId = null)
            }

            SpeechStoryboardIntent.EnterManagement -> enterManagement()

            SpeechStoryboardIntent.ExitManagement -> _uiState.update {
                it.copy(isManaging = false, selectedChapterIndexes = persistentSetOf())
            }

            is SpeechStoryboardIntent.ToggleChapterSelected -> _uiState.update { state ->
                val picked = state.selectedChapterIndexes.toMutableSet()
                if (!picked.remove(intent.chapterIndex)) picked += intent.chapterIndex
                state.copy(selectedChapterIndexes = picked.toImmutableSet())
            }

            SpeechStoryboardIntent.SelectAllChapters -> _uiState.update { state ->
                val all = state.chapters.map { it.chapterIndex }.toImmutableSet()
                state.copy(
                    selectedChapterIndexes =
                        if (state.selectedChapterIndexes.size == all.size) persistentSetOf() else all,
                )
            }

            SpeechStoryboardIntent.DeleteSelectedChapters -> deleteChapters(
                _uiState.value.selectedChapterIndexes.toList(),
            )

            is SpeechStoryboardIntent.DeleteChapter -> deleteChapters(listOf(intent.chapterIndex))
        }
    }

    private fun loadChapters(reason: String) {
        // 取消一律带人话原因：原因会沿协程树传下去，AI 日志里的"已取消"能直接写明
        // 是谁放弃了这一轮。注意"放弃回投"不等于"掐掉请求"——主链与场景层的 AI 调用
        // 都包了不可取消上下文，退页/切章都会跑完并落库。
        loadJob?.cancel(CancellationException("分镜${reason}，本轮加载不再回投界面（在飞的 AI 仍会跑完落库）"))
        sceneJob?.cancel(CancellationException("分镜${reason}，场景拆分不再回投界面（在飞的 AI 仍会跑完落库）"))
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    selectedChapterIndex = null,
                    isManaging = false,
                    selectedChapterIndexes = persistentSetOf(),
                )
            }
            try {
                val current = currentChapterIndex()
                val policy = splitPolicy()
                val analyzed = chapterSpeechGateway
                    .getChapterSummaries(bookUrl, currentResolverVersion(policy))
                    .map { summary ->
                    StoryboardChapterUi(
                        chapterIndex = summary.chapterIndex,
                        title = summary.title.ifBlank { fallbackTitle(summary.chapterIndex) },
                        segmentCount = summary.segmentCount,
                        characterCount = summary.characterCount,
                        isCurrent = summary.chapterIndex == current,
                        // 由旧划分方式/旧分析模式生成的那份，朗读时不会用它
                        usesCurrentConfig = summary.isCurrent,
                    )
                }
                // 正在读的那一章还没分析过也要能点进去, 进去才触发分析
                val chapters = if (current != null && analyzed.none { it.chapterIndex == current }) {
                    (analyzed + StoryboardChapterUi(
                        chapterIndex = current,
                        title = ReadBook.readerChapterInputWindow.current?.displayTitle?.takeUnless { it.isBlank() }
                            ?: fallbackTitle(current),
                        segmentCount = 0,
                        characterCount = 0,
                        isCurrent = true,
                        usesCurrentConfig = true,
                    )).sortedBy(StoryboardChapterUi::chapterIndex)
                } else {
                    analyzed
                }
                currentPlan = emptyList()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        multiSpeakerEnabled = ReadConfig.useMultiSpeaker,
                        analysisMode = ReadConfig.speechAnalysisMode,
                        chapters = chapters.toImmutableList(),
                        selectedChapterIndex = null,
                        chapterTitle = "",
                        isCurrentChapter = false,
                        scenes = persistentListOf(),
                        summary = null,
                        previewingItemId = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false) }
                toast(e.localizedMessage ?: appCtx.getString(R.string.load_failed))
            }
        }
    }

    private fun loadChapter(chapterIndex: Int, reanalyze: Boolean) {
        loadJob?.cancel(CancellationException("分镜切到第${chapterIndex + 1}章，上一章的加载不再回投界面（在飞的 AI 仍会跑完落库）"))
        sceneJob?.cancel(CancellationException("分镜切到第${chapterIndex + 1}章，上一章的场景拆分不再回投界面（在飞的 AI 仍会跑完落库）"))
        stopPreview()
        val title = _uiState.value.chapters
            .firstOrNull { it.chapterIndex == chapterIndex }
            ?.title
            ?: fallbackTitle(chapterIndex)
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, selectedChapterIndex = chapterIndex, chapterTitle = title)
            }
            try {
                val isCurrent = chapterIndex == currentChapterIndex()
                val policy = splitPolicy()
                val stored = withContext(Dispatchers.IO) {
                    chapterSpeechGateway.getChapterSegmentsFor(
                        bookUrl,
                        chapterIndex,
                        currentResolverVersion(policy),
                    )
                }
                val plan = when {
                    reanalyze -> analyzeChapter(chapterIndex, policy, resetCache = true)

                    stored.segments.isNotEmpty() -> {
                        // 朗读期间这章的分析已经落在库里，直接读缓存即可，
                        // 避免和正在进行的朗读并发再发一次 AI（重复生成 / 一直转圈）。
                        // 当前章和非当前章在这里是同一条路：都读库 + 套覆盖，
                        // 区别只在于非当前章拿不到段落时不套。
                        cachedChapterPlan(chapterIndex, policy, stored)
                    }

                    // 库里没有这一章：只有正在读的那一章能现重建段落去跑完整链路，
                    // 非当前章没有段落来源，也别为它白烧一次 AI。
                    isCurrent -> analyzeChapter(chapterIndex, policy, resetCache = false)

                    else -> emptyList()
                }
                currentPlan = plan
                val items = plan.map(::toItemUi)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        multiSpeakerEnabled = ReadConfig.useMultiSpeaker,
                        analysisMode = ReadConfig.speechAnalysisMode,
                        chapterTitle = title,
                        isCurrentChapter = isCurrent,
                        usesCurrentConfig = reanalyze || stored.isCurrent,
                        scenes = groupIntoScenes(items).toImmutableList(),
                        summary = buildSummary(items),
                    )
                }
                attachScenes(chapterIndex, plan)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false) }
                toast(e.localizedMessage ?: appCtx.getString(R.string.load_failed))
            }
        }
    }

    /**
     * 场景拆分是按需补跑的锦上添花层，绝不挡详情页渲染：
     * 扁平列表先发布、试听先可用，AI 再慢也在后台跑，成功才重新归组。
     *
     * 取消只放弃"回投这一页"，不掐请求（见 [withScenes]）：切章/退页后那一轮照样跑完落库，
     * 下次进这章直接命中缓存。
     */
    private fun attachScenes(chapterIndex: Int, plan: List<SpeechPlanItem>) {
        sceneJob?.cancel(CancellationException("分镜切到第${chapterIndex + 1}章，上一次场景拆分不再回投界面（在飞的 AI 仍会跑完落库）"))
        sceneJob = viewModelScope.launch {
            val enriched = withScenes(chapterIndex, plan)
            // 跳过/失败时 withScenes 原样返回同一个实例，不必重发状态
            if (enriched === plan) return@launch
            if (_uiState.value.selectedChapterIndex != chapterIndex) return@launch
            currentPlan = enriched
            val items = enriched.map(::toItemUi)
            _uiState.update {
                it.copy(
                    scenes = groupIntoScenes(items).toImmutableList(),
                    summary = buildSummary(items),
                )
            }
        }
    }

    /** 与朗读服务同一份划分口径：读设置、定 policy，段落重建靠它对齐章内位置。 */
private fun splitPolicy(): ContentSplitPolicy {
        val splitMode = ContentSplitPolicies.resolve(
            ReadAloudContentSplitMode.fromStorage(ReadConfig.contentSplitMode),
            ReadConfig.useMultiSpeaker,
        )
        return ContentSplitPolicies.forMode(splitMode, ReadConfig.contentSplitSymbols)
    }

    /**
     * 当前设置会写出的分析版本号。
     *
     * 只依赖设置项，不需要正文段落也不需要人物档案，所以非当前章也能算出来——这是分镜页能判断
     * "库里那份是不是当前设置生成的"的前提。AI 模式的版本号取不到（没配模型 / 请求失败）时
     * 退回规则版本，与主链的降级口径一致。
     */
    private suspend fun currentResolverVersion(policy: ContentSplitPolicy): String {
        val rule = ruleResolverVersion(policy)
        val mode = SpeechAnalysisMode.fromStorage(ReadConfig.speechAnalysisMode)
        if (mode == SpeechAnalysisMode.Rule) return rule
        return runCatching { refineSpeechWithAi.resolverVersion(mode, policy) }
            .getOrDefault(rule)
    }

    /** 正在读的那一章：走完整分析链，拿得到最新的说话人与音色。 */
    private suspend fun analyzeChapter(
        chapterIndex: Int,
        policy: ContentSplitPolicy,
        resetCache: Boolean,
    ): List<SpeechPlanItem> {
        val paragraphs = chapterParagraphs(chapterIndex, policy) ?: return emptyList()
        if (resetCache) {
            withContext(Dispatchers.IO) {
                chapterSpeechGateway.deleteChapter(bookUrl, chapterIndex)
            }
            resolveEffectiveSpeechPlan.clearAiCooldown(bookUrl)
            sceneAttemptedChapters.remove(chapterIndex)
            // 这一章的分析从头再来一遍：还在飞的场景拆分算作废，不许拿旧分段回写
            sceneGeneration++
        }
        return resolveEffectiveSpeechPlan(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            paragraphs = paragraphs,
            splitPolicy = policy,
            analysisMode = SpeechAnalysisMode.fromStorage(ReadConfig.speechAnalysisMode),
            // 分镜页触发的分析在 AI 日志里要能看出出处，不混进"朗读"
            source = "分镜",
        )
    }

    /**
     * 与朗读服务同口径重建段落：同一划分方式与符号策略下，段落文本和章内位置一致，
     * 分镜展示才能和朗读时落库的分析结果对得上。
     *
     * 章节输入窗口里带的是上/当前/下一章三份已解析正文，所以这里按章号在窗口里找，
     * 而不是只认当前章——非当前章只要还在窗口里就重建得出段落，覆盖层才套得上。
     */
    private suspend fun chapterParagraphs(
        chapterIndex: Int,
        policy: ContentSplitPolicy,
    ): List<io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph>? {
        val window = ReadBook.readerChapterInputWindow
        val input = listOf(window.previous, window.current, window.next)
            .firstOrNull { it?.chapter?.index == chapterIndex } ?: return null
        val semanticContent = input.source.semanticContent
        val displayTitle = input.displayTitle
        return withContext(Dispatchers.Default) {
            ReaderReadAloudChapter.create(
                chapterIndex = chapterIndex,
                title = displayTitle,
                // 分镜只用整段视图，页边界不参与段落生成
                semanticContent = semanticContent,
                pageStarts = listOf(0),
                contentSplitMode = policy.mode,
            ).canonicalSpeechParagraphs(
                splitByPage = false,
                policy = policy,
            )
        }
    }

    /**
     * 读库里存下来的分段算计划（不重跑分析），再套一层分配表覆盖。
     *
     * 覆盖这一步不能省：正文胶囊改的是分配表，朗读时发声的是覆盖**之后**的计划。
     * 过去只有当前章且段落取得到才套，多数时候分镜页显示的是覆盖之前的音色，
     * 用户改完胶囊在这里看不到，就成了"改了没用"。
     */
    private suspend fun cachedChapterPlan(
        chapterIndex: Int,
        policy: ContentSplitPolicy,
        stored: ChapterSpeechSegments,
    ): List<SpeechPlanItem> {
        val base = withContext(Dispatchers.Default) { buildCachedPlan(stored.segments) }
        if (base.isEmpty()) return base
        val paragraphs = chapterParagraphs(chapterIndex, policy) ?: return base
        // 同源闸门：覆盖层按正文段落重新定位说话人，段落必须和分析当时用的那一份一字不差。
        // 对不上（改过「正文显示角色名」、正文或划分口径变过、退回的是旧设置那份）就不套——
        // 拿错坐标去套胶囊，屏幕上显示的会是把归属安错人的结果。
        val hash = SpeechIdentity.chapterContentHash(paragraphs)
        if (stored.contentHash.isBlank() || hash != stored.contentHash) {
            val reason = if (stored.contentHash.isBlank()) {
                "库里那份不是当前设置生成的，没有可比对的正文基准"
            } else {
                "重建的段落与分析当时不是同一份（正文标记或划分口径变过）"
            }
            AppLog.put("分镜页第${chapterIndex + 1}章跳过分配表覆盖：$reason")
            return base
        }
        return overlayOnCached(chapterIndex, paragraphs, base)
    }

    /** 落库分段 + 当前音色绑定 + 分配表覆盖：分镜页看到的必须与朗读同源。 */
    private suspend fun overlayOnCached(
        chapterIndex: Int,
        paragraphs: List<io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph>,
        base: List<SpeechPlanItem>,
    ): List<SpeechPlanItem> = runCatching {
        resolveEffectiveSpeechPlan.overlayOnSegments(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            paragraphs = paragraphs,
            plan = base,
        )
    }.onFailure {
        AppLog.put("分镜页套用分配表失败，本章按原计划显示\n${it.localizedMessage}", it)
    }.getOrDefault(base)

    /**
     * 缺场景且是 AI 分析模式时按需补一次场景拆分，成功才写回库。
     *
     * 这是场景拆分唯一的发起入口：主链（朗读/预合成）只跑说话人分析、不产出场景，
     * 场景只对分镜页的阅读呈现有用、对朗读本身零消费，所以留到点开该章时再按需补。
     * 章节列表按当前音色重算过 [SpeechPlanItem]，这里只把场景字段搬回 plan 里，
     * 音色保持本次算出来的结果不变。
     *
     * 取消口径与主链对齐：请求和落库都包在不可取消上下文里，退页/返回/切章只是不再
     * 回投界面；真正的作废事件只有"这一章重新分析"和"删除这一章"（[sceneGeneration]）。
     */
    private suspend fun withScenes(
        chapterIndex: Int,
        plan: List<SpeechPlanItem>,
    ): List<SpeechPlanItem> {
        if (plan.isEmpty()) return plan
        if (SpeechAnalysisMode.fromStorage(ReadConfig.speechAnalysisMode) == SpeechAnalysisMode.Rule) {
            return plan
        }
        val segments = plan.map(SpeechPlanItem::segment)
        if (segments.none { it.sceneIndex == 0 }) {
            return plan
        }
        if (!sceneAttemptedChapters.add(chapterIndex)) return plan
        val generation = sceneGeneration
        val analysisId = segments.first().analysisId
        val enriched = try {
            // 取消口径与说话人分析主链一致：AI 请求和回写库都不随"离开这一页"中断。
            // 用户点开看一眼、没等完就退去别的地方，掐掉等于白烧一次 token，
            // 下次进这章还得从零再要一遍；跑完落库才是"看一眼"该有的代价。
            // 回写是先删后插，所以只认"这一章当前那份分析"：期间被重新分析/删除过就放弃回写。
            withContext(NonCancellable + Dispatchers.IO) {
                sceneMutex.withLock {
                    val scenes = refineSpeechWithAi.assignScenes(segments)
                    if (scenes.none { it.sceneIndex > 0 } ||
                        generation != sceneGeneration ||
                        !analysisStillCurrent(chapterIndex, analysisId)
                    ) {
                        return@withLock plan
                    }
                    // 回写只改场景两列，且写回的是**库里自己那批分段**：plan 里的行可能是分配表
                    // 覆盖切过的碎片（正文、起止点都被改过），拿碎片整份 replace 等于把分析段顶掉，
                    // 一次场景分组就能弄坏这一章朗读用的分段。碎片与父段同 id，所以场景天然按父段落库。
                    val stored = chapterSpeechGateway.getSegments(analysisId)
                    if (stored.isEmpty()) return@withLock plan
                    val scenesById = scenes.associateBy({ it.id }, { it.sceneIndex to it.sceneTitle })
                    chapterSpeechGateway.replaceSegments(
                        analysisId,
                        stored.map { row ->
                            val scene = scenesById[row.id] ?: return@map row
                            if (row.sceneIndex == scene.first && row.sceneTitle == scene.second) {
                                row
                            } else {
                                row.copy(sceneIndex = scene.first, sceneTitle = scene.second)
                            }
                        },
                    )
                    plan.map { item ->
                        val scene = scenesById[item.segment.id] ?: return@map item
                        if (item.segment.sceneIndex == scene.first) {
                            item
                        } else {
                            item.copy(
                                segment = item.segment.copy(sceneIndex = scene.first, sceneTitle = scene.second)
                            )
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            // 走到这里的取消只能来自请求本身（超时等），不再来自退页；
            // 撤掉记账，下次进这章还会补跑
            sceneAttemptedChapters.remove(chapterIndex)
            throw e
        } catch (e: Throwable) {
            // 拆分失败原本连日志都不留，还留着"这一章试过了"的记账：页面看着就是"场景永远出不来"，
            // 用户既不知道它失败过，也不会再有机会重试。撤账 + 说清楚是谁失败了。
            sceneAttemptedChapters.remove(chapterIndex)
            val reason = e.localizedMessage ?: e.javaClass.simpleName
            AppLog.put("分镜场景拆分失败：第${chapterIndex + 1}章本轮没有场景分组，$reason")
            toast(appCtx.getString(R.string.speech_storyboard_scene_failed, chapterIndex + 1, reason))
            return plan
        }
        if (enriched === plan) return plan
        return enriched
    }

    /** 库里这一章当前那份分析还是不是我手上这份：被删除或被重新分析换掉就不是了 */
    private suspend fun analysisStillCurrent(chapterIndex: Int, analysisId: String): Boolean =
        chapterSpeechGateway.getChapterSegments(bookUrl, chapterIndex)
            .firstOrNull()?.analysisId == analysisId

    /** 落库分段按当前音色绑定算成计划（见 [ResolveEffectiveSpeechPlanUseCase.planFromSegments]）。 */
    private suspend fun buildCachedPlan(
        segments: List<io.legado.app.domain.model.readaloud.ChapterSpeechSegment>,
    ): List<SpeechPlanItem> = resolveEffectiveSpeechPlan.planFromSegments(
        bookUrl = bookUrl,
        segments = segments,
        characterPerformances = characterPerformances(),
    )

    /** 按「连续相同 sceneIndex」分组；没有任何场景时给一个 sceneNumber=0 的占位组按扁平渲染 */
    private fun groupIntoScenes(items: List<StoryboardItemUi>): List<StoryboardSceneUi> {
        if (items.isEmpty()) return emptyList()
        if (items.all { it.sceneIndex == 0 }) {
            return listOf(StoryboardSceneUi(sceneNumber = 0, title = "", items = items.toImmutableList()))
        }
        val groups = mutableListOf<MutableList<StoryboardItemUi>>()
        var lastScene = Int.MIN_VALUE
        items.forEach { item ->
            if (groups.isEmpty() || item.sceneIndex != lastScene) {
                groups.add(mutableListOf())
            }
            groups.last() += item
            lastScene = item.sceneIndex
        }
        return groups.map { group ->
            val head = group.first()
            StoryboardSceneUi(
                sceneNumber = head.sceneIndex,
                title = head.sceneTitle,
                items = group.toImmutableList(),
            )
        }
    }

    private fun buildSummary(items: List<StoryboardItemUi>): StoryboardSummaryUi? {
        if (items.isEmpty()) return null
        return StoryboardSummaryUi(
            sceneCount = items.map { it.sceneIndex }.filter { it > 0 }.distinct().size,
            segmentCount = items.size,
            dialogueCount = items.count { it.role == StoryboardRole.Character },
            personCount = items.map { it.speakerName }.filter { it.isNotBlank() }.distinct().size,
        )
    }

    private suspend fun characterPerformances(): Map<String, CharacterPerformanceProfile> =
        withContext(Dispatchers.IO) {
            bookKnowledgeGateway.getCharacterProfiles(
                bookUrl = bookUrl,
                limit = 200,
                includeDrafts = true,
            ).filter {
                it.status == BookCharacterProfile.STATUS_ACTIVE ||
                    it.status == BookCharacterProfile.STATUS_DRAFT
            }.associate { profile ->
                profile.id to CharacterPerformanceProfile(
                    characterId = profile.id,
                    role = profile.role,
                    voiceGender = profile.voiceGender,
                    voiceAgeBand = profile.voiceAgeBand,
                    personality = profile.personality,
                    updatedAt = profile.updatedAt,
                )
            }
        }

    /** 正在读的这本书的当前章，换了书或新引擎章节输入还没发布就返回 null */
    private fun currentChapterIndex(): Int? = ReadBook.durChapterIndex
        .takeIf { ReadBook.book?.bookUrl == bookUrl && ReadBook.readerChapterInputWindow.current != null }

    /** 目录里没有标题（本地书清过目录）时的兜底名字 */
    private fun fallbackTitle(chapterIndex: Int): String =
        appCtx.getString(R.string.speech_storyboard_chapter_number, chapterIndex + 1)

    private fun toItemUi(item: SpeechPlanItem): StoryboardItemUi {
        val segment = item.segment
        return StoryboardItemUi(
            id = segment.id,
            text = segment.text,
            role = when (segment.roleType) {
                SpeechRoleType.Narrator -> StoryboardRole.Narrator
                SpeechRoleType.Thought -> StoryboardRole.Thought
                SpeechRoleType.Character -> StoryboardRole.Character
                else -> StoryboardRole.Unknown
            },
            speakerName = segment.characterName,
            // 只显示音色名：displayName 对带多音色的源是 `引擎名 · 音色名`，行里放不下
            voiceName = item.voice?.voiceLabel.orEmpty(),
            emotion = segment.emotion,
            paragraphIndex = segment.paragraphIndex,
            chapterPosition = segment.chapterPosition,
            confidence = segment.confidence,
            source = when (segment.source) {
                SpeechResolutionSource.Rule -> StoryboardSource.Rule
                SpeechResolutionSource.Local -> StoryboardSource.Local
                SpeechResolutionSource.Ai -> StoryboardSource.Ai
                SpeechResolutionSource.User -> StoryboardSource.User
                SpeechResolutionSource.Fallback -> StoryboardSource.Fallback
            },
            locked = segment.userLocked,
            // 没绑定音色也能试听（回退引擎默认），按钮只在没有文本时才灰
            previewable = segment.text.isNotBlank(),
            sceneIndex = segment.sceneIndex,
            sceneTitle = segment.sceneTitle,
        )
    }

    /** 片段场景标题挂在 item 上，方便分组表头展示 */
    private fun enterManagement() {
        _uiState.update { it.copy(isManaging = true, selectedChapterIndexes = persistentSetOf()) }
    }

    private fun deleteChapters(indexes: List<Int>) {
        if (indexes.isEmpty()) {
            toast(appCtx.getString(R.string.speech_storyboard_delete_none))
            return
        }
        deleteJob?.cancel()
        deleteJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    indexes.forEach { chapterSpeechGateway.deleteChapter(bookUrl, it) }
                }
                // 删掉的章节分析已不在：在飞的场景拆分算作废（回写还会二次核对库里那份）
                sceneGeneration++
                loadChapters("删除章节后重载")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast(e.localizedMessage ?: appCtx.getString(R.string.delete_failed))
            }
        }
    }

    private fun previewSegment(itemId: String) {
        if (_uiState.value.previewingItemId == itemId) {
            stopPreview()
            return
        }
        val planItem = currentPlan.firstOrNull { it.segment.id == itemId }
        val text = planItem?.segment?.text?.trim()
        previewJob?.cancel()
        if (text.isNullOrEmpty()) {
            toast(appCtx.getString(R.string.speech_storyboard_preview_unavailable))
            return
        }
        previewJob = viewModelScope.launch {
            _uiState.update { it.copy(previewingItemId = itemId) }
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    // 没绑定音色不拦试听：与真实朗读同款，回退到协调器默认引擎路线
                    val voice = planItem?.voice ?: runtimeDefaultVoice() ?: return@withContext null
                    // 文件名不能用原始 segmentId：里面的「:」会被 MediaPlayer 当成协议前缀，
                    // prepare 直接 status=0x1。与 casting/cloudtts 页同款，用 hashCode 命名。
                    val file = File(appCtx.cacheDir, "storyboard_preview/${itemId.hashCode()}.audio")
                    file.parentFile?.mkdirs()
                    previewSynthesizer.synthesize(voice, text, file) to file
                }
            }
            outcome.fold(
                onSuccess = { result ->
                    when {
                        result == null -> {
                            clearPreview()
                            // 走到这里说明连兜底音色都造不出来，必须把当时引擎配置记下来
                            AppLog.put(
                                "分镜试听没有可用的兜底音色：engineType=${ReadAloud.coordinatorDefaultEngineType} " +
                                    "engineId=${ReadAloud.coordinatorDefaultEngineId.ifBlank { "（空）" }}"
                            )
                            toast(appCtx.getString(R.string.speech_storyboard_preview_unavailable))
                        }

                        result.first -> _effects.tryEmit(
                            SpeechStoryboardEffect.PlayPreview(result.second.absolutePath),
                        )

                        else -> {
                            clearPreview()
                            toast(appCtx.getString(R.string.voice_preview_failed))
                        }
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    clearPreview()
                    // 异常原来只弹一句「试听失败」就被吞了，日志里什么都不留
                    AppLog.put("分镜试听异常：${e.javaClass.name}: ${e.message}")
                    toast(appCtx.getString(R.string.voice_preview_failed))
                },
            )
        }
    }

    /**
     * 朗读服务在没绑定音色时用的同款兜底：按协调器默认引擎路线现造一条运行时音色，
     * 不落库、不参与分析，只保证「每段都能听」听到的是真实朗读的发声。
     */
    private fun runtimeDefaultVoice(): ReadAloudVoice? {
        val engineType = ReadAloud.coordinatorDefaultEngineType
        val engineId = ReadAloud.coordinatorDefaultEngineId
        // http 路线的 engineId 必须是可查的 HttpTTS 主键，否则与 VoicePreviewSynthesizer 的解析口径对不上
        if (engineType == ReadAloudVoice.ENGINE_HTTP && engineId.toLongOrNull() == null) return null
        return ReadAloudVoice(
            id = "storyboard-preview:$engineType:$engineId:$engineId",
            engineType = engineType,
            engineId = engineId,
            speakerId = ReadAloud.coordinatorDefaultSpeakerId,
            displayName = "",
        )
    }

    /**
     * 锁定 / 解锁这一条分镜。
     *
     * 锁定是"这一句的说话人我认可了"的唯一入口：现成的跳过锁定段判断会让重新分析不再动它，
     * 正文胶囊改归属走的是另一张分配表，钉不住分析结果本身。
     *
     * 写入只改 `userLocked` 那一列，**不能拿页面上这一行整份回写分段**——这一行可能是分配表
     * 覆盖切过之后的碎片（正文和起止点都被改过），回写会把库里分析段换成碎片。
     * 碎片与它所属的分析段共用同一个 id，所以锁定天然是"按段"生效、界面上同 id 的几行一起翻转，
     * 与库里真正被锁住的那一行是一致的。
     *
     * 这里不重启朗读、不重算计划：锁定只影响下一次重新分析，当前播出的声音不受影响。
     */
    private fun toggleSegmentLock(itemId: String) {
        val item = _uiState.value.scenes.flatMap { it.items }.firstOrNull { it.id == itemId }
        if (item == null) {
            // 按钮就长在这一行上，走到这里只能是状态刚被重分析换掉、点下去的那一行已经不在了。
            // 这不属于要打扰用户的失败，留一行日志给排查用。
            AppLog.put("分镜锁定段没有找到这一行：$itemId")
            return
        }
        val locked = !item.locked
        viewModelScope.launch {
            try {
                chapterSpeechGateway.setSegmentUserLocked(itemId, locked)
                _uiState.update { state ->
                    state.copy(
                        scenes = state.scenes.map { scene ->
                            if (scene.items.none { it.id == itemId }) {
                                scene
                            } else {
                                scene.copy(
                                    items = scene.items.map {
                                        if (it.id == itemId) it.copy(locked = locked) else it
                                    }.toImmutableList(),
                                )
                            }
                        }.toImmutableList(),
                    )
                }
                toast(
                    appCtx.getString(
                        if (locked) R.string.speech_storyboard_lock_on else R.string.speech_storyboard_lock_off,
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("分镜锁定段写入失败：$itemId\n${e.localizedMessage}", e)
                toast(e.localizedMessage ?: appCtx.getString(R.string.save_failed))
            }
        }
    }

    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        if (_uiState.value.previewingItemId != null) {
            _effects.tryEmit(SpeechStoryboardEffect.StopPreviewPlayback)
        }
        _uiState.update { it.copy(previewingItemId = null) }
    }

    private fun clearPreview() {
        _uiState.update { it.copy(previewingItemId = null) }
    }

    private fun toast(message: String) {
        _effects.tryEmit(SpeechStoryboardEffect.ShowToast(message))
    }
}
