package io.legado.app.ui.book.readingmemory.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import io.legado.app.constant.AppLog
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.ReadingMemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "ReadingMemDetailVM"

class ReadingMemoryDetailViewModel(
    val bookUrl: String,
    private val repository: ReadingMemoryRepository,
) : ViewModel() {

    private val _intent = MutableSharedFlow<ReadingMemoryDetailIntent>(extraBufferCapacity = 1)
    private val _effect = MutableSharedFlow<ReadingMemoryDetailEffect>(extraBufferCapacity = 1)
    val effect = _effect.asSharedFlow()

    private val _showReviewEditor = MutableStateFlow(false)
    private val _reviewDraft = MutableStateFlow("")
    private val _showAbandonedDialog = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val detailState: StateFlow<ReadingMemoryDetailUiState> = repository.observeByBookUrl(bookUrl)
        .flatMapLatest { memory ->
            AppLog.put("[阅读记忆] observeByBookUrl emit: memory=${memory?.bookName} url=$bookUrl")
            val stats = try {
                repository.computeStatistics(bookUrl)
            } catch (e: Exception) {
                AppLog.put("[阅读记忆] computeStatistics 失败 url=$bookUrl", e)
                ReadingStatistics(
                    totalReadTime = 0L,
                    readingDays = 0,
                    maxDayReadTime = 0L,
                    maxDayReadDate = null,
                    totalWords = 0L,
                )
            }
            val protagonists = memory?.protagonistsJson
                ?.split("|")
                ?.filter { it.isNotBlank() }
                ?: emptyList()

            _showReviewEditor.map { showEditor ->
                ReadingMemoryDetailUiState(
                    bookUrl = bookUrl,
                    bookName = memory?.bookName ?: "",
                    author = memory?.bookAuthor ?: "",
                    coverUrl = memory?.coverUrl,
                    intro = memory?.userModifiedIntro?.let { memory.intro }
                        ?: memory?.intro ?: "",
                    kind = memory?.kind ?: "",
                    wordCount = memory?.statTotalWords ?: 0,
                    rating = (memory?.rating ?: 0f).toInt(),
                    status = when {
                        memory == null -> 0
                        memory.abandoned -> 3
                        memory.progress >= 1f -> 2
                        memory.progress > 0f -> 1
                        else -> 0
                    },
                    abandoned = memory?.abandoned ?: false,
                    isStillOnShelf = true,
                    review = memory?.review ?: "",
                    userModifiedIntro = if (memory?.userModifiedIntro == true) memory.intro else null,
                    progressInfo = formatProgress(memory),
                    statistics = stats,
                    protagonistNames = protagonists,
                    tags = emptyList(),
                    loading = false,
                    showReviewEditor = showEditor,
                    reviewDraft = _reviewDraft.value,
                    showAbandonedDialog = _showAbandonedDialog.value,
                    showRatingEditor = false,
                )
            }
        }
        .catch { e ->
            AppLog.put("[阅读记忆] detailState flow 崩溃 url=$bookUrl", e)
            emit(ReadingMemoryDetailUiState(
                bookUrl = bookUrl,
                bookName = "加载失败",
                author = e.message ?: "未知错误",
                loading = false,
            ))
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), ReadingMemoryDetailUiState())

    init {
        AppLog.put("[阅读记忆] DetailVM init bookUrl='$bookUrl'")
        viewModelScope.launch {
            _intent.collectLatest { intent ->
                handleIntent(intent)
            }
        }
    }

    fun onIntent(intent: ReadingMemoryDetailIntent) {
        _intent.tryEmit(intent)
    }

    private suspend fun handleIntent(intent: ReadingMemoryDetailIntent) {
        when (intent) {
            is ReadingMemoryDetailIntent.Load -> {}
            is ReadingMemoryDetailIntent.SetRating -> setRating(intent.rating)
            is ReadingMemoryDetailIntent.OpenReviewEditor -> {
                _reviewDraft.value = intent.initial
                _showReviewEditor.value = true
            }
            is ReadingMemoryDetailIntent.UpdateReviewDraft -> _reviewDraft.value = intent.text
            is ReadingMemoryDetailIntent.SaveReview -> saveReview()
            is ReadingMemoryDetailIntent.DismissReviewEditor -> _showReviewEditor.value = false
            is ReadingMemoryDetailIntent.ToggleAbandoned -> toggleAbandoned(intent.abandoned)
            is ReadingMemoryDetailIntent.ConfirmAbandoned -> confirmAbandoned()
            is ReadingMemoryDetailIntent.DismissAbandonedDialog -> _showAbandonedDialog.value = false
            is ReadingMemoryDetailIntent.EditIntro -> editIntro(intent.intro)
            is ReadingMemoryDetailIntent.Refresh -> refresh()
            is ReadingMemoryDetailIntent.NavigateBack -> _effect.emit(ReadingMemoryDetailEffect.Back)
        }
    }

    private suspend fun setRating(rating: Int) {
        repository.updateRating(bookUrl, rating.toFloat())
        _effect.emit(ReadingMemoryDetailEffect.ShowToast("评分已更新"))
    }

    private suspend fun saveReview() {
        repository.updateReview(bookUrl, _reviewDraft.value)
        _showReviewEditor.value = false
        _effect.emit(ReadingMemoryDetailEffect.ShowToast("评价已保存"))
    }

    private suspend fun toggleAbandoned(abandoned: Boolean) {
        if (abandoned) {
            _showAbandonedDialog.value = true
        } else {
            repository.unmarkAbandoned(bookUrl)
            _effect.emit(ReadingMemoryDetailEffect.ShowToast("已取消弃文标记"))
        }
    }

    private suspend fun confirmAbandoned() {
        repository.markAbandoned(bookUrl)
        _showAbandonedDialog.value = false
        _effect.emit(ReadingMemoryDetailEffect.ShowToast("已标记为弃文"))
    }

    private suspend fun editIntro(intro: String) {
        repository.updateIntro(bookUrl, intro)
        _effect.emit(ReadingMemoryDetailEffect.ShowToast("简介已更新"))
    }

    private suspend fun refresh() {
        repository.syncFromBook(bookUrl)
        _effect.emit(ReadingMemoryDetailEffect.ShowToast("已同步"))
    }

    private fun formatProgress(memory: io.legado.app.data.entities.ReadingMemory?): String {
        if (memory == null) return ""
        val cur = memory.durChapterIndex
        val total = memory.totalChapterNum
        return if (cur > 0 && total > 0) "$cur/$total 章" else ""
    }
}
