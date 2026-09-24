package io.legado.app.ui.book.readaloud.storyboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ContentSplitPolicies
import io.legado.app.domain.model.readaloud.SpeechAnalysisMode
import io.legado.app.domain.model.readaloud.SpeechPlanItem
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.usecase.BuildSpeechPlanUseCase
import io.legado.app.domain.usecase.PrepareChapterSpeechPlanUseCase
import io.legado.app.domain.usecase.RefineSpeechWithAiUseCase
import io.legado.app.feature.reader.core.readaloud.ReaderReadAloudChapter
import io.legado.app.help.readaloud.playback.VoicePreviewSynthesizer
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
 * 正在读的那一章走完整分析链（可以重新分析），其它章只把库里存下来的分段读出来 ——
 * 分段按朗读服务的同一划分口径从新引擎章节输入重建，单独去数据库捞正文再切一次只会和朗读时对不上。
 *
 * 场景分组是朗读分析之外按需补跑的一层（[RefineSpeechWithAiUseCase.assignScenes]）：
 * 只有 AI 分析模式的分镜页会触发，成功才写回库；失败保持扁平列表，不影响朗读。
 */
class SpeechStoryboardViewModel(
    private val bookUrl: String,
    private val prepareChapterSpeechPlan: PrepareChapterSpeechPlanUseCase,
    private val buildSpeechPlan: BuildSpeechPlanUseCase,
    private val chapterSpeechGateway: ChapterSpeechGateway,
    private val bookKnowledgeGateway: BookKnowledgeGateway,
    private val refineSpeechWithAi: RefineSpeechWithAiUseCase,
    private val previewSynthesizer: VoicePreviewSynthesizer,
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

    /** 本次打开页面内尝试过场景拆分的章节，失败的不重打 AI */
    private val sceneAttemptedChapters = hashSetOf<Int>()

    init {
        loadChapters()
    }

    override fun onCleared() {
        previewJob?.cancel()
        previewJob = null
        super.onCleared()
    }

    fun onIntent(intent: SpeechStoryboardIntent) {
        val selected = _uiState.value.selectedChapterIndex
        when (intent) {
            SpeechStoryboardIntent.Refresh ->
                if (selected == null) loadChapters() else loadChapter(selected, reanalyze = false)

            SpeechStoryboardIntent.Reanalyze ->
                selected?.let { loadChapter(it, reanalyze = true) }

            is SpeechStoryboardIntent.OpenChapter -> loadChapter(intent.chapterIndex, reanalyze = false)

            SpeechStoryboardIntent.BackToChapters -> {
                stopPreview()
                loadChapters()
            }

            is SpeechStoryboardIntent.PreviewSegment -> previewSegment(intent.itemId)

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

    private fun loadChapters() {
        loadJob?.cancel()
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
                val analyzed = chapterSpeechGateway.getChapterSummaries(bookUrl).map { summary ->
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
        loadJob?.cancel()
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
                var plan = when {
                    reanalyze && isCurrent -> currentChapterPlan(chapterIndex, true)
                    isCurrent -> {
                        // 朗读期间这章的分析已经落在库里，直接读缓存即可，
                        // 避免和正在进行的朗读并发再发一次 AI（重复生成 / 一直转圈）。
                        // 只有库里还没有这章分析结果（比如还没起播就先进分镜页看）时才跑完整链路。
                        val cached = withContext(Dispatchers.IO) {
                            chapterSpeechGateway.getChapterSegments(bookUrl, chapterIndex)
                        }
                        if (cached.isNotEmpty()) {
                            cachedChapterPlan(chapterIndex)
                        } else {
                            currentChapterPlan(chapterIndex, false)
                        }
                    }

                    else -> cachedChapterPlan(chapterIndex)
                }
                plan = withScenes(chapterIndex, plan)
                currentPlan = plan
                val items = plan.map(::toItemUi)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        multiSpeakerEnabled = ReadConfig.useMultiSpeaker,
                        analysisMode = ReadConfig.speechAnalysisMode,
                        chapterTitle = title,
                        isCurrentChapter = isCurrent,
                        scenes = groupIntoScenes(items).toImmutableList(),
                        summary = buildSummary(items),
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

    /** 正在读的那一章：走完整分析链，拿得到最新的说话人与音色 */
    private suspend fun currentChapterPlan(
        chapterIndex: Int,
        reanalyze: Boolean,
    ): List<SpeechPlanItem> {
        val input = ReadBook.readerChapterInputWindow.current
            ?.takeIf { it.chapter.index == chapterIndex } ?: return emptyList()
        // 与朗读服务同口径重建段落：同一划分方式与符号策略下，段落文本和章内位置一致，
        // 分镜展示才能和朗读时落库的分析结果对得上。
        val splitMode = ContentSplitPolicies.resolve(
            ReadAloudContentSplitMode.fromStorage(ReadConfig.contentSplitMode),
            ReadConfig.useMultiSpeaker,
        )
        val splitPolicy = ContentSplitPolicies.forMode(splitMode, ReadConfig.contentSplitSymbols)
        val paragraphs = withContext(Dispatchers.Default) {
            ReaderReadAloudChapter.create(
                chapterIndex = chapterIndex,
                title = input.displayTitle,
                semanticContent = input.source.semanticContent,
                // 分镜只用整段视图，页边界不参与段落生成
                pageStarts = listOf(0),
                contentSplitMode = splitMode,
            ).canonicalSpeechParagraphs(
                splitByPage = false,
                policy = splitPolicy,
            )
        }
        if (reanalyze) {
            withContext(Dispatchers.IO) {
                chapterSpeechGateway.deleteChapter(bookUrl, chapterIndex)
            }
            prepareChapterSpeechPlan.clearAiCooldown(bookUrl)
            sceneAttemptedChapters.remove(chapterIndex)
        }
        return prepareChapterSpeechPlan(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            paragraphs = paragraphs,
            analysisMode = SpeechAnalysisMode.fromStorage(ReadConfig.speechAnalysisMode),
            useMultiSpeaker = ReadConfig.useMultiSpeaker,
            policy = splitPolicy,
        )
    }

    /** 其它章：只读库里存下来的分段，按当前音色绑定算一遍音色，不重新分析 */
    private suspend fun cachedChapterPlan(chapterIndex: Int): List<SpeechPlanItem> {
        val segments = chapterSpeechGateway.getChapterSegments(bookUrl, chapterIndex)
        if (segments.isEmpty()) return emptyList()
        return buildSpeechPlan(
            bookUrl = bookUrl,
            segments = segments,
            characterPerformances = characterPerformances(),
            useMultiSpeaker = ReadConfig.useMultiSpeaker,
        )
    }

    /**
     * 缺场景且是 AI 分析模式时按需补一次场景拆分，成功才写回库。
     *
     * 章节列表按当前音色重算过 [SpeechPlanItem]，这里只把场景字段搬回 plan 里，
     * 音色保持本次算出来的结果不变。
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
        val enriched = runCatching { refineSpeechWithAi.assignScenes(segments) }.getOrNull()
            ?: return plan
        if (enriched.all { it.sceneIndex == 0 }) return plan
        withContext(Dispatchers.IO) {
            chapterSpeechGateway.replaceSegments(segments.first().analysisId, enriched)
        }
        val scenesById = enriched.associateBy({ it.id }, { it.sceneIndex to it.sceneTitle })
        return plan.map { item ->
            val scene = scenesById[item.segment.id] ?: return@map item
            if (item.segment.sceneIndex == scene.first) {
                item
            } else {
                item.copy(segment = item.segment.copy(sceneIndex = scene.first, sceneTitle = scene.second))
            }
        }
    }

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
                groups += mutableListOf()
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
            voiceName = item.voice?.displayName.orEmpty(),
            emotion = segment.emotion,
            paragraphIndex = segment.paragraphIndex,
            confidence = segment.confidence,
            source = when (segment.source) {
                SpeechResolutionSource.Rule -> StoryboardSource.Rule
                SpeechResolutionSource.Local -> StoryboardSource.Local
                SpeechResolutionSource.Ai -> StoryboardSource.Ai
                SpeechResolutionSource.User -> StoryboardSource.User
                SpeechResolutionSource.Fallback -> StoryboardSource.Fallback
            },
            locked = segment.userLocked,
            previewable = item.voice != null && segment.text.isNotBlank(),
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
                loadChapters()
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
        val voice = planItem?.voice
        val text = planItem?.segment?.text?.trim()
        previewJob?.cancel()
        if (voice == null || text.isNullOrEmpty()) {
            toast(appCtx.getString(R.string.speech_storyboard_preview_unavailable))
            return
        }
        previewJob = viewModelScope.launch {
            _uiState.update { it.copy(previewingItemId = itemId) }
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val file = File(appCtx.cacheDir, "storyboard_preview/$itemId.audio")
                    file.parentFile?.mkdirs()
                    previewSynthesizer.synthesize(voice, text, file) to file
                }
            }
            outcome.fold(
                onSuccess = { (ok, file) ->
                    if (ok) {
                        _effects.tryEmit(SpeechStoryboardEffect.PlayPreview(file.absolutePath))
                    } else {
                        clearPreview()
                        toast(appCtx.getString(R.string.voice_preview_failed))
                    }
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    clearPreview()
                    toast(appCtx.getString(R.string.voice_preview_failed))
                },
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
}
