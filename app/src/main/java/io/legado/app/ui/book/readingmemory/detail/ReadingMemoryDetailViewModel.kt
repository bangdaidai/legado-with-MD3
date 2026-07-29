package io.legado.app.ui.book.readingmemory.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.Book
import io.legado.app.help.book.TagManager
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.repository.ReadingMemoryRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

class ReadingMemoryDetailViewModel(
    private val bookUrl: String,
    private val repository: ReadingMemoryRepository,
) : ViewModel() {

    // 主角实时源变化触发器：增删主角后强制重算
    private val protagonistRefresh = MutableStateFlow(Unit)

    // 书签/摘录变化触发器：编辑或删除书签后强制重算
    private val bookmarkRefresh = MutableStateFlow(Unit)

    private val _showReviewEditor = MutableStateFlow(false)
    private val _reviewDraft = MutableStateFlow("")
    private val _showTagPicker = MutableStateFlow(false)

    private val _effectFlow = MutableSharedFlow<ReadingMemoryDetailEffect>(extraBufferCapacity = 1)
    val effectFlow: SharedFlow<ReadingMemoryDetailEffect> = _effectFlow.asSharedFlow()
    val effect: SharedFlow<ReadingMemoryDetailEffect> = effectFlow

    val intentFlow = MutableSharedFlow<ReadingMemoryDetailIntent>(extraBufferCapacity = 1)

    val detailState = combine(
        repository.observeByBookUrl(bookUrl),
        protagonistRefresh,
        _showTagPicker,
        bookmarkRefresh,
        repository.observeTagColorMap(),
    ) { memory, _, tagPicker, _, tagColorMap -> memory to tagPicker to tagColorMap }
        .flatMapLatest { (memoryAbandoned, tagColorMap) ->
            val (memory, _) = memoryAbandoned
            val book = repository.getBook(bookUrl)
            val statistics = repository.computeStatistics(bookUrl)
            val excerpts = repository.getExcerpts(bookUrl)
            val protagonists = repository.getProtagonistNames(bookUrl)
            val readRecordTimelineDays = repository.getReadRecordTimelineDays(bookUrl)
            val readRecordTotalTime = repository.getReadRecordTotalTime(bookUrl)
            val excludedTags = repository.getExcludedTags()
            val bookKindTags = book?.kind
                ?.split(",", "|", "\n", "，", "、")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            val availableTags = (repository.getAvailableTags() + bookKindTags)
                .filter { !TagManager.isExcluded(it, excludedTags) }
                .distinct()
            _showReviewEditor.combine(_reviewDraft) { showReview, draft ->
                val safeMemory = memory ?: ReadingMemory.defaultStub(bookUrl)
                val abandoned = memory?.abandoned ?: false
                val progress = safeMemory.progress
                val status = when {
                    abandoned -> 3
                    progress >= 1f -> 2
                    progress > 0f -> 1
                    else -> 0
                }
                val statusText = when (status) {
                    0 -> "待看"
                    1 -> "在读"
                    2 -> "已读"
                    else -> "弃文"
                }
                val kindSource = if (book != null) book.kind else memory?.kind
                val customTagSource = book?.customTag ?: memory?.customTag
                val tags = TagManager.bookDisplayTags(
                    kind = kindSource,
                    customTag = customTagSource,
                )
                val intro = if (book != null && memory?.userModifiedIntro != true) {
                    book.intro ?: memory?.intro ?: ""
                } else {
                    memory?.intro ?: book?.intro ?: ""
                }
                val firstReadDate = readRecordTimelineDays.firstOrNull()?.date
                val totalBookWords = (book?.wordCount ?: memory?.wordCount ?: "0")
                    .toLongOrNull() ?: 0L
                val totalReadWords = statistics?.totalWords ?: 0L
                val remainingWords = (totalBookWords - totalReadWords).coerceAtLeast(0L)
                val excerptCount = excerpts.size
                val totalChapterCount = safeMemory.totalChapterNum
                val durChapterIdx = safeMemory.durChapterIndex
                ReadingMemoryDetailUiState(
                    bookUrl = bookUrl,
                    bookName = book?.name ?: memory?.bookName ?: "",
                    author = book?.author ?: memory?.bookAuthor ?: "",
                    coverUrl = memory?.coverUrl,
                    intro = intro,
                    kind = kindSource ?: "",
                    wordCount = 0L,
                    wordCountText = book?.wordCount ?: memory?.wordCount ?: "",
                    rating = safeMemory.rating,
                    status = status,
                    statusText = statusText,
                    abandoned = abandoned,
                    isStillOnShelf = book != null,
                    review = safeMemory.review ?: "",
                    userModifiedIntro = safeMemory.userModifiedIntro,
                    progress = progress,
                    progressInfo = if (safeMemory.totalChapterNum > 0) {
                        "第 ${safeMemory.durChapterIndex + 1} 章 / 共 ${safeMemory.totalChapterNum} 章"
                    } else {
                        "第 ${safeMemory.durChapterIndex + 1} 章"
                    },
                    annotationCount = safeMemory.annotationCount,
                    lastReadTime = safeMemory.lastReadTime,
                    statistics = statistics,
                    protagonistNames = protagonists,
                    tags = tags,
                    excerpts = excerpts,
                    readRecordTimelineDays = readRecordTimelineDays,
                    readRecordTotalTime = readRecordTotalTime,
                    availableTags = availableTags,
                    tagColorMap = tagColorMap,
                    loading = memory == null,
                    showReviewEditor = showReview,
                    reviewDraft = draft,
                    showTagPicker = _showTagPicker.value,
                    showRatingEditor = false,
                    firstReadDate = firstReadDate,
                    totalReadWords = totalReadWords,
                    remainingWords = remainingWords,
                    excerptCount = excerptCount,
                    totalChapterCount = totalChapterCount,
                    durChapterIndex = durChapterIdx,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingMemoryDetailUiState())

    init {
        viewModelScope.launch {
            intentFlow
                .onEach { intent ->
                    when (intent) {
                        is ReadingMemoryDetailIntent.Load -> {
                            repository.ensureMemory(bookUrl)
                            repository.autoExtractProtagonists(bookUrl)
                            protagonistRefresh.value = Unit
                        }
                        is ReadingMemoryDetailIntent.Refresh -> {
                            repository.ensureMemory(bookUrl)
                            repository.autoExtractProtagonists(bookUrl)
                            protagonistRefresh.value = Unit
                        }
                        is ReadingMemoryDetailIntent.SetRating -> {
                            repository.updateRating(bookUrl, intent.rating)
                        }
                        is ReadingMemoryDetailIntent.OpenReviewEditor -> {
                            _reviewDraft.value = intent.initial
                            _showReviewEditor.value = true
                        }
                        is ReadingMemoryDetailIntent.UpdateReviewDraft -> {
                            _reviewDraft.value = intent.text
                        }
                        is ReadingMemoryDetailIntent.SaveReview -> {
                            repository.updateReview(bookUrl, _reviewDraft.value)
                            _showReviewEditor.value = false
                        }
                        is ReadingMemoryDetailIntent.DismissReviewEditor -> {
                            _showReviewEditor.value = false
                        }
                        is ReadingMemoryDetailIntent.SetStatus -> {
                            if (intent.abandoned) repository.markAbandoned(bookUrl)
                            else repository.unmarkAbandoned(bookUrl)
                            protagonistRefresh.value = Unit
                        }
                        is ReadingMemoryDetailIntent.OpenTagPicker -> {
                            _showTagPicker.value = true
                        }
                        is ReadingMemoryDetailIntent.DismissTagPicker -> {
                            _showTagPicker.value = false
                        }
                        is ReadingMemoryDetailIntent.AddTag -> {
                            repository.addTag(bookUrl, intent.tag)
                            protagonistRefresh.value = Unit
                        }
                        is ReadingMemoryDetailIntent.RemoveTag -> {
                            repository.removeTag(bookUrl, intent.tag)
                            protagonistRefresh.value = Unit
                        }
                        is ReadingMemoryDetailIntent.EditIntro -> {
                            repository.updateIntro(bookUrl, intent.intro)
                        }
                        is ReadingMemoryDetailIntent.NavigateBack -> {
                            _effectFlow.tryEmit(ReadingMemoryDetailEffect.Back)
                        }
                        is ReadingMemoryDetailIntent.OpenBookInfo -> {
                            _effectFlow.tryEmit(
                                ReadingMemoryDetailEffect.NavigateToBookInfo(
                                    name = detailState.value.bookName,
                                    author = detailState.value.author,
                                    bookUrl = bookUrl,
                                ),
                            )
                        }
                        is ReadingMemoryDetailIntent.AddProtagonist -> {
                            repository.setProtagonist(bookUrl, intent.name, true)
                            protagonistRefresh.value = Unit
                        }
                        is ReadingMemoryDetailIntent.RemoveProtagonist -> {
                            repository.setProtagonist(bookUrl, intent.name, false)
                            protagonistRefresh.value = Unit
                        }
                        is ReadingMemoryDetailIntent.DeleteReview -> {
                            repository.deleteReview(bookUrl)
                            _showReviewEditor.value = false
                        }
                        is ReadingMemoryDetailIntent.EditBookmark -> {
                            repository.saveBookmark(intent.bookmark)
                            bookmarkRefresh.value = Unit
                        }
                        is ReadingMemoryDetailIntent.DeleteBookmark -> {
                            repository.deleteBookmark(intent.bookmark)
                            bookmarkRefresh.value = Unit
                        }
                    }
                }
                .launchIn(viewModelScope)
        }
    }

    fun onIntent(intent: ReadingMemoryDetailIntent) {
        intentFlow.tryEmit(intent)
    }
}
