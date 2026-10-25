package io.legado.app.ui.book.readaloud.storyboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysis
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ContentSplitPolicy
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechAnalysisMode
import io.legado.app.domain.model.readaloud.SpeechAnalysisStatus
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechPlanItem
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.usecase.ResolveEffectiveSpeechPlanUseCase
import io.legado.app.feature.reader.core.readaloud.ReaderReadAloudChapter
import io.legado.app.help.readaloud.analysis.ChapterSpeechBatchAnalyzer
import io.legado.app.help.readaloud.cast.CastSegmentFinalizer
import io.legado.app.help.readaloud.cast.VoicePoolStore
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 * 场景分组不再由这一页按需补跑：它挂在**该章说话人分析收尾**（见
 * io.legado.app.domain.usecase.RefineSpeechWithAiUseCase），分析跑完场景就在库里，这一页只读不算。
 * 历史上分析过、当时还没拆场景的旧章，用本页的「重新分析本章」补；拆分失败只写日志，
 * 界面按扁平列表降级显示，不影响朗读。
 */
class SpeechStoryboardViewModel(
    private val bookUrl: String,
    private val resolveEffectiveSpeechPlan: ResolveEffectiveSpeechPlanUseCase,
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
    private var previewJob: Job? = null
    private var deleteJob: Job? = null

    /** 详情页当前加载的完整计划，试听按 id 找回音色；离开详情页清空 */
    private var currentPlan: List<SpeechPlanItem> = emptyList()

    /**
     * 本次进页已经写进日志的分析说明（章号 + 分析行时间戳），避免来回切章重复记。
     *
     * 回落原因同时进 AppLog：分镜页看到的结果与设置不是一档时，日志里要能查到是哪一步退的。
     */
    private val notifiedAnalysisNotes = mutableSetOf<String>()

    /**
     * 章节列表上「往后补分析 / 重新分析选中章」这一批跑批任务。
     *
     * 与 loadJob 分开持有：跑批期间用户照样能点进某一章看细节，两条链互不打断。
     */
    private var batchJob: Job? = null

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
        // 取消带原因：退页不再掐 AI（主链跑完落库），这一刀只放弃把结果回投界面，
        // AI 日志里留下的取消条目说的是"谁放弃了结果"，不是"谁杀了请求"
        loadJob?.cancel(CancellationException("退出分镜页，本轮加载不再回投界面（在飞的 AI 仍会跑完落库）"))
        batchJob?.cancel(
            CancellationException("退出分镜页，批量分析不再开下一章（在飞的那一章仍会跑完落库）")
        )
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

            is SpeechStoryboardIntent.OpenSpeakerPicker -> _uiState.update {
                it.copy(assigningItemId = intent.itemId)
            }

            SpeechStoryboardIntent.CloseSpeakerPicker -> _uiState.update {
                it.copy(assigningItemId = null)
            }

            is SpeechStoryboardIntent.AssignSegmentSpeaker ->
                assignSegmentSpeaker(intent.itemId, intent.speaker)

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

            SpeechStoryboardIntent.AnalyzeSelectedChapters -> analyzeSelectedChapters()

            SpeechStoryboardIntent.CancelBatchAnalysis -> batchJob?.cancel(
                CancellationException("用户在分镜页停止批量分析（在飞的那一章仍会跑完落库）")
            )
        }
    }

    private fun loadChapters(reason: String) {
        // 取消一律带人话原因：原因会沿协程树传下去，AI 日志里的"已取消"能直接写明
        // 是谁放弃了这一轮。注意"放弃回投"不等于"掐掉请求"——主链与场景层的 AI 调用
        // 都包了不可取消上下文，退页/切章都会跑完并落库。
        loadJob?.cancel(CancellationException("分镜$reason，本轮加载不再回投界面（在飞的 AI 仍会跑完落库）"))
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
                val analyzed = chapterSpeechGateway
                    .getChapterSummaries(bookUrl)
                    .map { summary ->
                    StoryboardChapterUi(
                        chapterIndex = summary.chapterIndex,
                        title = summary.title.ifBlank { fallbackTitle(summary.chapterIndex) },
                        segmentCount = summary.segmentCount,
                        characterCount = summary.characterCount,
                        isCurrent = summary.chapterIndex == current,
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
                        assigningItemId = null,
                        speakerOptions = persistentListOf(),
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
        stopPreview()
        val title = _uiState.value.chapters
            .firstOrNull { it.chapterIndex == chapterIndex }
            ?.title
            ?: fallbackTitle(chapterIndex)
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    selectedChapterIndex = chapterIndex,
                    chapterTitle = title,
                    // 加载期间先按设置那一档占位，算完再换成这一章的实际口径
                    analysisMode = ReadConfig.speechAnalysisMode,
                )
            }
            try {
                val isCurrent = chapterIndex == currentChapterIndex()
                val policy = splitPolicy()
                val stored = withContext(Dispatchers.IO) {
                    chapterSpeechGateway.getChapterSegments(bookUrl, chapterIndex)
                }
                val plan = when {
                    reanalyze -> analyzeChapter(chapterIndex, policy, resetCache = true)

                    stored.isNotEmpty() -> {
                        // 朗读期间这章的分析已经落在库里，直接读缓存即可，
                        // 避免和正在进行的朗读并发再发一次 AI（重复生成 / 一直转圈）。
                        // 当前章和非当前章在这里是同一条路：都读库 + 套覆盖，
                        // 区别只在于非当前章拿不到段落时不套。
                        // 这一份就是当时那一份：改了划分方式 / 模型 / 提示词也不重跑，
                        // 屏幕上要看到念过的那一版，重跑只认「重新分析」这一下。
                        cachedChapterPlan(chapterIndex, policy, stored)
                    }

                    // 库里没有这一章：只有正在读的那一章能现重建段落去跑完整链路，
                    // 非当前章没有段落来源，也别为它白烧一次 AI。
                    isCurrent -> analyzeChapter(chapterIndex, policy, resetCache = false)

                    else -> emptyList()
                }
                currentPlan = plan
                val items = plan.map(::toItemUi)
                val speakers = speakerOptions()
                // 配置卡这一行写的是**这一章实际**按哪一档跑出来的，不是设置里选的那一档：
                // AI 失败回落、或者库里只有规则结果时，照抄设置会把规则结果标成 AI 补全
                val storedAnalysis = withContext(Dispatchers.IO) {
                    chapterSpeechGateway.getStoredAnalysis(bookUrl, chapterIndex)
                }
                val display = resolveAnalysisDisplay(chapterIndex, storedAnalysis)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        multiSpeakerEnabled = ReadConfig.useMultiSpeaker,
                        analysisMode = display.mode.storageValue,
                        chapterTitle = title,
                        isCurrentChapter = isCurrent,
                        scenes = groupIntoScenes(items).toImmutableList(),
                        summary = buildSummary(items),
                        assigningItemId = null,
                        speakerOptions = speakers.toImmutableList(),
                    )
                }
                // 缓存的详情页不会重跑 AI，也就没有回落事件可接：这一屏的提示由页面自己补。
                // 重新分析/首次分析由 PrepareChapterSpeechPlanUseCase 发事件，这里只记日志，免得弹两次
                reportAnalysisFallback(
                    chapterIndex = chapterIndex,
                    analysis = storedAnalysis,
                    display = display,
                    showToast = !reanalyze && stored.isNotEmpty(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false) }
                toast(e.localizedMessage ?: appCtx.getString(R.string.load_failed))
            }
        }
    }

    /**
     * 与朗读服务、补分析跑批同一份划分口径（见 [ChapterSpeechBatchAnalyzer.splitPolicy]）。
     *
     * 三处共用一个实现是底线：只有**没跑过的章**才按这一份现切，改了设置之后如果三处口径不一，
     * 新补出来的那一份就和耳朵里那一份对不上。已经跑过的章不在这个话题里——那一屏照用旧的那份。
     */
    private fun splitPolicy(): ContentSplitPolicy = ChapterSpeechBatchAnalyzer.splitPolicy()

    /** 详情页「分析模式」要展示的实际口径，以及进 AppLog 的回落说明。 */
    private data class AnalysisDisplay(
        val mode: SpeechAnalysisMode,
        /** 进 AppLog 的完整说明；空串不记 */
        val logMessage: String = "",
    )

    /**
     * 还原这一章**实际**是哪一档跑出来的。
     *
     * 标签只认库里记的那一档：版本串里没有 AI 标记 = 规则；AI 复核失败并回落（status=Failed）
     * = 规则；其余按版本串里记的那一档原样显示。AI 有没有真的落下说话人结论只写日志、
     * 不改标签——AI 跑过但判「全旁白」也是 AI 的结果，拿结论有无当判据会把跑过的 AI 档说成规则。
     *
     * 认 AI 版本看版本串里的 `ai-speech-analysis-` 标记，不拿当前划分方式去比：库里那一份
     * 可能是别的划分方式下跑的，按策略比会把旧规则结果误认成 AI 版本。
     */
    private suspend fun resolveAnalysisDisplay(
        chapterIndex: Int,
        analysis: ChapterSpeechAnalysis?,
    ): AnalysisDisplay {
        val requested = SpeechAnalysisMode.fromStorage(ReadConfig.speechAnalysisMode)
        if (analysis == null) return AnalysisDisplay(requested)
        if (!analysis.resolverVersion.contains(AI_RESOLVER_MARKER)) {
            if (requested == SpeechAnalysisMode.Rule) return AnalysisDisplay(requested)
            return AnalysisDisplay(
                mode = SpeechAnalysisMode.Rule,
                logMessage = "分镜页第${chapterIndex + 1}章展示的是规则结果：库里那一份按规则分析，" +
                    "当前设置是 AI 补全；要按 AI 重跑请点「重新分析」",
            )
        }
        if (analysis.status == SpeechAnalysisStatus.Failed) {
            // 用户已经切回纯规则：实际结果就是规则，不必再拿上一轮的 AI 失败提醒他
            if (requested == SpeechAnalysisMode.Rule) return AnalysisDisplay(SpeechAnalysisMode.Rule)
            return AnalysisDisplay(
                mode = SpeechAnalysisMode.Rule,
                logMessage = "分镜页第${chapterIndex + 1}章展示的是规则结果：AI 分析失败" +
                    analysis.error.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty() +
                    "；要重跑请点「重新分析」",
            )
        }
        val storedMode = aiModeOf(analysis.resolverVersion)
        val segments = withContext(Dispatchers.IO) { chapterSpeechGateway.getSegments(analysis.id) }
        // 标签不改：这一章按哪一档跑的就写哪一档。下面这行只回答「为什么看不到 AI 改过的段」
        if (segments.none { it.source == SpeechResolutionSource.Ai }) {
            val unresolvedDialogue = segments.any {
                it.source == SpeechResolutionSource.Rule &&
                    it.characterId == null &&
                    (it.roleType == SpeechRoleType.Character || it.roleType == SpeechRoleType.Thought)
            }
            val reason = if (unresolvedDialogue) {
                "AI 没有返回可用的说话人结论，这些段仍是规则结果；要重跑请点「重新分析」"
            } else {
                "库里这一份没有任何 AI 结论（整章仍是旁白）；若是整段/按页划分且这一章分析得早，" +
                    "点「重新分析」会先按引号拆出对白再交给 AI"
            }
            val logKey = "ai-empty:$chapterIndex:${analysis.updatedAt}"
            if (notifiedAnalysisNotes.add(logKey)) {
                AppLog.put("分镜页第${chapterIndex + 1}章这一份记的是「${storedMode.logLabel()}」档，但$reason")
            }
        }
        return AnalysisDisplay(storedMode)
    }

    /** 日志里用的档位短名（界面文案带「（推荐）」等后缀，写进日志太啰嗦）。 */
    private fun SpeechAnalysisMode.logLabel(): String = when (this) {
        SpeechAnalysisMode.Rule -> "规则"
        SpeechAnalysisMode.RuleWithAi -> "AI 补全"
        SpeechAnalysisMode.AiUnderstanding -> "AI 理解"
    }

    /** 从 AI 解析版本串里认出当时那一档（纯规则版本不会走到这里）。 */
    private fun aiModeOf(resolverVersion: String): SpeechAnalysisMode = when {
        resolverVersion.contains(":${SpeechAnalysisMode.AiUnderstanding.storageValue}") ->
            SpeechAnalysisMode.AiUnderstanding
        resolverVersion.contains(":${SpeechAnalysisMode.RuleWithAi.storageValue}") ->
            SpeechAnalysisMode.RuleWithAi
        else -> SpeechAnalysisMode.Rule
    }

    private fun reportAnalysisFallback(
        chapterIndex: Int,
        analysis: ChapterSpeechAnalysis?,
        display: AnalysisDisplay,
        showToast: Boolean,
    ) {
        if (display.logMessage.isBlank()) return
        val key = "$chapterIndex:${analysis?.updatedAt ?: 0L}:${display.logMessage}"
        if (!notifiedAnalysisNotes.add(key)) return
        AppLog.put(display.logMessage)
        if (showToast) {
            toast(appCtx.getString(R.string.speech_storyboard_analysis_fallback_toast))
        }
    }

    /**
     * 现跑一次完整分析链：先拿到这一章的朗读段落，再交给 [ResolveEffectiveSpeechPlanUseCase]。
     *
     * 段落优先从当前引擎章节输入窗口取（正在读的那一章必然在里面，且那一份就是屏幕与朗读共用的
     * 带标记正文）；取不到再从本地正文重建——后者让「重新分析本章」对**任何下载过正文的章**都
     * 成立，不再只有正在读的那一章能重跑（旧口径下非当前章点重分析等于白点）。
     */
    private suspend fun analyzeChapter(
        chapterIndex: Int,
        policy: ContentSplitPolicy,
        resetCache: Boolean,
    ): List<SpeechPlanItem> {
        val paragraphs = chapterParagraphs(chapterIndex, policy)
            ?: ChapterSpeechBatchAnalyzer.paragraphsOf(bookUrl, chapterIndex, policy)
            ?: return emptyList()
        if (resetCache) {
            withContext(Dispatchers.IO) {
                chapterSpeechGateway.deleteChapter(bookUrl, chapterIndex)
            }
        }
        return resolveEffectiveSpeechPlan(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            paragraphs = paragraphs,
            splitPolicy = policy,
            analysisMode = SpeechAnalysisMode.fromStorage(ReadConfig.speechAnalysisMode),
            // 分镜页触发的分析在 AI 日志里要能看出出处，不混进"朗读"
            source = "分镜",
            // 「重新分析」是用户显式点的那一下：跳过本书 AI 失败冷却，也不去等同章在飞的那一轮。
            // 冷却是给自动流程省 token 的，不该把用户主动重跑也一起冻住；首次分析（非重跑）照旧受冷却管。
            ignoreAiCooldown = resetCache,
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
        stored: List<io.legado.app.domain.model.readaloud.ChapterSpeechSegment>,
    ): List<SpeechPlanItem> {
        val base = withContext(Dispatchers.Default) { buildCachedPlan(stored) }
        if (base.isEmpty()) return base
        val paragraphs = chapterParagraphs(chapterIndex, policy) ?: return base
        // 同源核对：覆盖层按正文段落重新定位说话人，那几段必须还量得到现在这份正文。
        // 判据**不比内容哈希**——换了划分方式之后两边哈希必然不等，可那几段落在这页正文同一些字上，
        // 覆盖照样套得上。真对不上的只有正文换过（重新下载、替换规则、开关「显示角色」），
        // 那种情况拿错坐标去套胶囊，屏幕上显示的会是把归属安错人的结果，所以不套。
        if (!SpeechIdentity.alignsWithParagraphs(paragraphs, stored)) {
            AppLog.put(
                "分镜页第${chapterIndex + 1}章跳过分配表覆盖：库里那几段量不到现在这份正文" +
                    "（正文换过了，重新下载或替换规则、开关「显示角色」都会这样）"
            )
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
                    // 与朗读侧同一来源（ResolveLocalSpeakersUseCase）：池名供表演指令与
                    // 自动选音的候选域使用，缺了它这一份与耳朵里那份会各挑各的
                    voicePoolLabel = VoicePoolStore.poolLabelOf(profile),
                    personality = profile.personality,
                    updatedAt = profile.updatedAt,
                )
            }
        }

    /**
     * 「改归属」弹窗的候选：旁白 + 这本书启用中的人物，外加 AI 认出来但还没进配音列表的临时人物。
     *
     * 来源与 [characterPerformances] 是同一份档案表（`book_character_profiles`，显式带上草稿），
     * 所以这里点中的那一个就是选音那一步查得到音色绑定的那一个——候选与发声不会各认各的人。
     * 停用的人物不进候选：那是从配音列表删掉的人，正文里再把他长回来只会「查无此人」。
     *
     * 随本章一起加载，不在点开那一下现查：这一页是核对清单，弹窗先空一帧再填会像没点开。
     */
    private suspend fun speakerOptions(): List<StoryboardSpeakerUi> =
        withContext(Dispatchers.IO) {
            val profiles = bookKnowledgeGateway.getCharacterProfiles(
                bookUrl = bookUrl,
                limit = 200,
                includeDrafts = true,
            ).filter {
                it.status == BookCharacterProfile.STATUS_ACTIVE ||
                    it.status == BookCharacterProfile.STATUS_DRAFT
            }.filter { it.name.isNotBlank() }
                .sortedWith(
                    compareBy({ it.status != BookCharacterProfile.STATUS_ACTIVE }, { it.name })
                )
                .map { profile ->
                    StoryboardSpeakerUi(
                        characterId = profile.id,
                        name = profile.name,
                        isDraft = profile.status == BookCharacterProfile.STATUS_DRAFT,
                        poolLabel = VoicePoolStore.poolLabelOf(profile),
                    )
                }
            buildList {
                add(
                    StoryboardSpeakerUi(
                        characterId = "",
                        name = appCtx.getString(R.string.voice_role_narrator),
                        isNarrator = true,
                    ),
                )
                addAll(profiles)
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
                loadChapters("删除章节后重载")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast(e.localizedMessage ?: appCtx.getString(R.string.delete_failed))
            }
        }
    }

    /**
     * 把勾选的章交给 [ChapterSpeechBatchAnalyzer] 逐章补分析——旧「AI 分配角色」弹窗里"选起止章
     * 跑一批"那条路在这页的既有语法里落地：勾章这件事本来就有（批量删除用的同一套选择）。
     *
     * **续跑是免费的**：已经按当前设置分析过的章被跳过，一次请求都不发，所以全选重跑一遍
     * 只会补上没跑成的那几章。要重做某一章用详情页的「重新分析」（它会先删掉那一章的旧结论）。
     *
     * 跑批期间可以照常点进某一章看细节：两条链各自独立，[batchJob] 与 [loadJob] 互不打断。
     * 「停止」只关掉后面还没开的章，在飞的那一章照样跑完落库（分析链把 AI 请求和落库包在
     * 不可取消上下文里），下次进来直接命中。
     */
    private fun analyzeSelectedChapters() {
        if (_uiState.value.batchRunning) return
        val targets = _uiState.value.selectedChapterIndexes.toList().sorted()
        if (targets.isEmpty()) {
            // 「还没有选择章节」这条现成的话批量删除也在用，不另开一份
            toast(appCtx.getString(R.string.speech_storyboard_delete_none))
            return
        }
        if (!ChapterSpeechBatchAnalyzer.isWorthRunning()) {
            // 纯规则模式下"分析"就是本地切分，翻到那章当场算出来，提前跑一遍只是白占时间
            toast(appCtx.getString(R.string.speech_storyboard_batch_not_worth))
            return
        }
        batchJob = viewModelScope.launch {
            _uiState.update { it.copy(batchRunning = true, batchDone = 0, batchTotal = targets.size) }
            val outcomes = try {
                ChapterSpeechBatchAnalyzer.analyze(
                    bookUrl = bookUrl,
                    chapterIndexes = targets,
                    source = "分镜批量",
                ) { _uiState.update { it.copy(batchDone = it.batchDone + 1) } }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(batchRunning = false, batchDone = 0, batchTotal = 0) }
                throw e
            }
            _uiState.update { it.copy(batchRunning = false, batchDone = 0, batchTotal = 0) }
            val analyzed = outcomes.count { it.result == ChapterSpeechBatchAnalyzer.ChapterOutcome.Analyzed }
            val skipped = outcomes.count {
                it.result == ChapterSpeechBatchAnalyzer.ChapterOutcome.AlreadyAnalyzed
            }
            val missing = outcomes.count {
                it.result == ChapterSpeechBatchAnalyzer.ChapterOutcome.ContentMissing
            }
            val failed = outcomes.count { it.result == ChapterSpeechBatchAnalyzer.ChapterOutcome.Failed }
            outcomes.filter { it.result == ChapterSpeechBatchAnalyzer.ChapterOutcome.Failed }
                .forEach { AppLog.put("分镜批量分析失败：第${it.chapterIndex + 1}章 ${it.error.orEmpty()}") }
            toast(
                appCtx.getString(
                    R.string.speech_storyboard_batch_summary,
                    analyzed, skipped, missing, failed,
                )
            )
            loadChapters("批量分析完成")
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
     * 改这一条分镜的说话人。
     *
     * 写的是真相表 `chapter_speech_segments` 那一行（正文胶囊那条链写的也是它，所以两边看得见
     * 同一个结论），并顺手把这几句**已经有**的分配行改成同一个人——不改的话下一次准备这一章时
     * [io.legado.app.help.readaloud.cast.CastSegmentFinalizer.replay] 会照分配表把刚写的这份顶回去，
     * 用户看到的就是「在这里改了等于没改」。为什么只改已有行不新建：新建会往正文里塞角色标记，
     * 坐标一移库里那几段就对不上现在这份正文了。
     *
     * 页面上那一行可能是分配覆盖切过的碎片，碎片与它所属的分析段共用同一个 id，所以这一刀
     * 天然是"按段"生效，与库里真正被写的那一行一致。
     *
     * 改过的段算用户定稿（`userLocked`），重新分析本章不再动它；要回 AI 那份原判断，
     * 用「重新分析本章」（它先把这一章的分析行删掉）。这里不重启朗读：正在播的那一句的声音不受影响，
     * 但从下一次出计划起念的是新那一位。
     */
    private fun assignSegmentSpeaker(itemId: String, speaker: StoryboardSpeakerUi) {
        val chapterIndex = _uiState.value.selectedChapterIndex ?: return
        val item = _uiState.value.scenes.flatMap { it.items }.firstOrNull { it.id == itemId }
        if (item == null) {
            // 按钮就长在这一行上，走到这里只能是状态刚被重分析换掉、点下去的那一行已经不在了。
            // 这不属于要打扰用户的失败，留一行日志给排查用。
            AppLog.put("分镜改归属没有找到这一行：$itemId")
            _uiState.update { it.copy(assigningItemId = null) }
            return
        }
        val narrator = speaker.isNarrator
        // 心声（正文里单引号那一句）改完人还是心声，只有原本不是角色的段才转成对白
        val roleType = when {
            narrator -> SpeechRoleType.Narrator
            item.role == StoryboardRole.Thought -> SpeechRoleType.Thought
            else -> SpeechRoleType.Character
        }
        viewModelScope.launch {
            try {
                CastSegmentFinalizer.reattribute(
                    bookUrl = bookUrl,
                    chapterIndex = chapterIndex,
                    segmentId = itemId,
                    roleType = roleType,
                    characterId = if (narrator) null else speaker.characterId,
                    characterName = if (narrator) "" else speaker.name,
                    voicePoolLabel = speaker.poolLabel,
                )
                _uiState.update { it.copy(assigningItemId = null) }
                reloadChapterDetail(chapterIndex)
                toast(
                    if (narrator) {
                        appCtx.getString(R.string.speech_storyboard_assign_narrator)
                    } else {
                        appCtx.getString(R.string.speech_storyboard_assign_to, speaker.name)
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("分镜改归属写入失败：$itemId → ${speaker.name}\n${e.localizedMessage}", e)
                _uiState.update { it.copy(assigningItemId = null) }
                toast(e.localizedMessage ?: appCtx.getString(R.string.save_failed))
            }
        }
    }

    /**
     * 改完归属之后安静地重算这一章：读刚写好的那几段 → 按现在的音色绑定选音 → 套分配覆盖 → 换列表。
     *
     * 重算而不是手工改那一行：这一行的音色是新那一位的绑定算出来的（
     * [io.legado.app.domain.usecase.BuildSpeechPlanUseCase]），概要里的人数也要跟着变。
     * 手改只能改显示，改完页面与耳朵就不是同一份了。
     *
     * 与 [loadChapter] 的缓存分支同一条路，差别只在不置 isLoading——改一句话不该让整屏闪一次加载页，
     * 也不重跑分析（库里已经有这一章的分段，一个 token 都不该再花）。
     */
    private suspend fun reloadChapterDetail(chapterIndex: Int) {
        val stored = withContext(Dispatchers.IO) {
            chapterSpeechGateway.getChapterSegments(bookUrl, chapterIndex)
        }
        if (stored.isEmpty()) return
        val plan = cachedChapterPlan(chapterIndex, splitPolicy(), stored)
        // 等结果的这段时间里用户可能已经切章或退回列表：那一屏的计划不是这一章的，不能回投
        if (chapterIndex != _uiState.value.selectedChapterIndex) return
        currentPlan = plan
        val items = plan.map(::toItemUi)
        _uiState.update {
            it.copy(
                scenes = groupIntoScenes(items).toImmutableList(),
                summary = buildSummary(items),
            )
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

    private companion object {
        /** AI 分析版本串里的固定标记；纯规则版本只有规则段，没有这一段 */
        const val AI_RESOLVER_MARKER = "ai-speech-analysis-"
    }
}
