package io.legado.app.ui.book.readaloud.storyboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.readaloud.SpeechAnalysisMode
import io.legado.app.domain.model.readaloud.SpeechPlanItem
import io.legado.app.domain.model.readaloud.SpeechRoleType
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
 * 分镜结果页：把当前这一章的分段、判定出的说话人、分到的音色摆出来。
 *
 * 只看 [ReadBook] 里正在读的那一章 —— 分镜依赖 `TextChapter` 的排版结果，
 * 单独去数据库捞正文再排一次版只会和朗读时的分段对不上。
 */
class SpeechStoryboardViewModel(
    private val bookUrl: String,
    private val prepareChapterSpeechPlan: PrepareChapterSpeechPlanUseCase,
    private val chapterSpeechGateway: ChapterSpeechGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpeechStoryboardUiState(bookUrl = bookUrl))
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SpeechStoryboardEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    private var loadJob: Job? = null

    init {
        load(reanalyze = false)
    }

    fun onIntent(intent: SpeechStoryboardIntent) {
        when (intent) {
            SpeechStoryboardIntent.Refresh -> load(reanalyze = false)
            SpeechStoryboardIntent.Reanalyze -> load(reanalyze = true)
        }
    }

    private fun load(reanalyze: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val chapter = ReadBook.curTextChapter
                if (chapter == null || ReadBook.book?.bookUrl != bookUrl) {
                    _uiState.update {
                        it.copy(isLoading = false, items = persistentListOf(), chapterTitle = "")
                    }
                    toast(appCtx.getString(R.string.speech_storyboard_no_chapter))
                    return@launch
                }
                val chapterIndex = ReadBook.durChapterIndex
                val paragraphs = withContext(Dispatchers.Default) {
                    chapter.toCanonicalSpeechParagraphs()
                }
                if (reanalyze) {
                    withContext(Dispatchers.IO) {
                        chapterSpeechGateway.deleteChapter(bookUrl, chapterIndex)
                    }
                }
                val plan = prepareChapterSpeechPlan(
                    bookUrl = bookUrl,
                    chapterIndex = chapterIndex,
                    paragraphs = paragraphs,
                    analysisMode = SpeechAnalysisMode.fromStorage(ReadConfig.speechAnalysisMode),
                    useMultiSpeaker = ReadConfig.useMultiSpeaker,
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        chapterTitle = chapter.title,
                        multiSpeakerEnabled = ReadConfig.useMultiSpeaker,
                        analysisMode = ReadConfig.speechAnalysisMode,
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

    private fun toItemUi(item: SpeechPlanItem): StoryboardItemUi {
        val segment = item.segment
        return StoryboardItemUi(
            id = segment.id,
            text = segment.text,
            role = when {
                segment.roleType == SpeechRoleType.Narrator -> StoryboardRole.Narrator
                segment.roleType == SpeechRoleType.Thought -> StoryboardRole.Thought
                segment.roleType == SpeechRoleType.Character -> StoryboardRole.Character
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
