package io.legado.app.ui.book.readaloud.storyboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.SpeechAnalysisMode
import io.legado.app.domain.model.readaloud.SpeechPlanItem
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.usecase.BuildSpeechPlanUseCase
import io.legado.app.domain.usecase.PrepareChapterSpeechPlanUseCase
import io.legado.app.help.readaloud.segment.toCanonicalSpeechParagraphs
import io.legado.app.model.ReadBook
import io.legado.app.ui.config.readConfig.ReadConfig
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
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
import splitties.init.appCtx

/**
 * 分镜结果页：先按章列出分析过的章节，点进去看这一章的分段、说话人和音色。
 *
 * 正在读的那一章走完整分析链（可以重新分析），其它章只把库里存下来的分段读出来 ——
 * 分段依赖 `TextChapter` 的排版结果，单独去数据库捞正文再排一次版只会和朗读时对不上。
 */
class SpeechStoryboardViewModel(
    private val bookUrl: String,
    private val prepareChapterSpeechPlan: PrepareChapterSpeechPlanUseCase,
    private val buildSpeechPlan: BuildSpeechPlanUseCase,
    private val chapterSpeechGateway: ChapterSpeechGateway,
    private val bookKnowledgeGateway: BookKnowledgeGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpeechStoryboardUiState(bookUrl = bookUrl))
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SpeechStoryboardEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    private var loadJob: Job? = null

    init {
        loadChapters()
    }

    fun onIntent(intent: SpeechStoryboardIntent) {
        val selected = _uiState.value.selectedChapterIndex
        when (intent) {
            SpeechStoryboardIntent.Refresh ->
                if (selected == null) loadChapters() else loadChapter(selected, reanalyze = false)

            SpeechStoryboardIntent.Reanalyze ->
                selected?.let { loadChapter(it, reanalyze = true) }

            is SpeechStoryboardIntent.OpenChapter ->
                loadChapter(intent.chapterIndex, reanalyze = false)

            SpeechStoryboardIntent.BackToChapters -> loadChapters()
        }
    }

    private fun loadChapters() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedChapterIndex = null) }
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
                        title = ReadBook.curTextChapter?.title?.takeUnless { it.isBlank() }
                            ?: fallbackTitle(current),
                        segmentCount = 0,
                        characterCount = 0,
                        isCurrent = true,
                    )).sortedBy(StoryboardChapterUi::chapterIndex)
                } else {
                    analyzed
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        multiSpeakerEnabled = ReadConfig.useMultiSpeaker,
                        analysisMode = ReadConfig.speechAnalysisMode,
                        chapters = chapters.toImmutableList(),
                        selectedChapterIndex = null,
                        chapterTitle = "",
                        isCurrentChapter = false,
                        items = persistentListOf(),
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
                val plan = if (isCurrent) {
                    currentChapterPlan(chapterIndex, reanalyze)
                } else {
                    cachedChapterPlan(chapterIndex)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        multiSpeakerEnabled = ReadConfig.useMultiSpeaker,
                        analysisMode = ReadConfig.speechAnalysisMode,
                        chapterTitle = title,
                        isCurrentChapter = isCurrent,
                        items = plan.map(::toItemUi).toImmutableList(),
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
        val chapter = ReadBook.curTextChapter ?: return emptyList()
        val paragraphs = withContext(Dispatchers.Default) {
            chapter.toCanonicalSpeechParagraphs()
        }
        if (reanalyze) {
            withContext(Dispatchers.IO) {
                chapterSpeechGateway.deleteChapter(bookUrl, chapterIndex)
            }
        }
        return prepareChapterSpeechPlan(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            paragraphs = paragraphs,
            analysisMode = SpeechAnalysisMode.fromStorage(ReadConfig.speechAnalysisMode),
            useMultiSpeaker = ReadConfig.useMultiSpeaker,
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

    /** 正在读的这本书的当前章，换了书或还没排版好就返回 null */
    private fun currentChapterIndex(): Int? = ReadBook.durChapterIndex
        .takeIf { ReadBook.book?.bookUrl == bookUrl && ReadBook.curTextChapter != null }

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
        )
    }

    private fun toast(message: String) {
        _effects.tryEmit(SpeechStoryboardEffect.ShowToast(message))
    }
}
