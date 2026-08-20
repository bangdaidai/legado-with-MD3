package io.legado.app.ui.book.readingmemory.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.domain.gateway.ThemeSettingsGateway
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.help.book.TagManager
import io.legado.app.ui.book.read.page.provider.TextChapterLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

class ReadingMemoryDetailViewModel(
    private val bookUrl: String,
    private val repository: ReadingMemoryRepository,
    private val themeSettingsGateway: ThemeSettingsGateway,
    private val bookshelfSettingsGateway: BookshelfSettingsGateway,
) : ViewModel() {

    // 主角实时源变化触发器：增删主角后强制重算
    private val protagonistRefresh = MutableStateFlow(0)

    // 书签/摘录变化触发器：编辑或删除书签后强制重算
    private val bookmarkRefresh = MutableStateFlow(0)

    private val _showReviewEditor = MutableStateFlow(false)
    private val _reviewDraft = MutableStateFlow("")
    private val _showTagPicker = MutableStateFlow(false)

    // 分享卡片生成状态：由 GenerateShareCard / DismissShareCard 驱动
    private val _shareCardLoading = MutableStateFlow(false)
    private val _showShareCard = MutableStateFlow(false)
    private val _shareCardData = MutableStateFlow<io.legado.app.data.entities.ShareCardData?>(null)


    private val _effectFlow = MutableSharedFlow<ReadingMemoryDetailEffect>(extraBufferCapacity = 1)
    val effectFlow: SharedFlow<ReadingMemoryDetailEffect> = _effectFlow.asSharedFlow()
    val effect: SharedFlow<ReadingMemoryDetailEffect> = effectFlow

    val intentFlow = MutableSharedFlow<ReadingMemoryDetailIntent>(extraBufferCapacity = 1)

    private val baseDetailState = combine(
        repository.observeByBookUrl(bookUrl),
        protagonistRefresh,
        _showTagPicker,
        bookmarkRefresh,
        combine(
            repository.observeTagColorMap(),
            themeSettingsGateway.settings.map { it.enableCustomTagColors }
        ) { colors, enabled -> if (enabled) colors else emptyMap() },
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
            val tagGroups = repository.getTagGroups()
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
                    book.getDisplayIntro() ?: memory?.intro ?: ""
                } else {
                    memory?.intro ?: book?.getDisplayIntro() ?: ""
                }
                val firstReadDate = readRecordTimelineDays.firstOrNull()?.date
                val totalBookWords = parseTotalWords(book?.wordCount ?: memory?.wordCount)
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
                    tagGroups = tagGroups,
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
                    bookshelfTagBorder = bookshelfSettingsGateway.currentSettings.bookshelfTagBorder,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingMemoryDetailUiState())

    /** 在基础状态之上叠加分享卡片预览状态，避免生成分享卡片时重跑全部数据库查询 */
    val detailState = combine(
        baseDetailState,
        _shareCardLoading,
        _showShareCard,
        _shareCardData,
    ) { base, shareCardLoading, showShareCard, shareCardData ->
        base.copy(
            shareCardLoading = shareCardLoading,
            showShareCard = showShareCard,
            shareCardData = shareCardData,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingMemoryDetailUiState())


    init {
        viewModelScope.launch {
            intentFlow
                .onEach { intent ->
                    when (intent) {
                        is ReadingMemoryDetailIntent.Load -> {
                            repository.ensureMemory(bookUrl)
                            repository.autoExtractProtagonists(bookUrl)
                            protagonistRefresh.value++
                        }
                        is ReadingMemoryDetailIntent.Refresh -> {
                            repository.ensureMemory(bookUrl)
                            repository.autoExtractProtagonists(bookUrl)
                            protagonistRefresh.value++
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
                            protagonistRefresh.value++
                        }
                        is ReadingMemoryDetailIntent.OpenTagEdit -> {
                            _showTagPicker.value = true
                        }
                        is ReadingMemoryDetailIntent.OpenTagPicker -> {
                            _showTagPicker.value = true
                        }
                        is ReadingMemoryDetailIntent.DismissTagPicker -> {
                            _showTagPicker.value = false
                        }
                        is ReadingMemoryDetailIntent.AddTag -> {
                            repository.addTag(bookUrl, intent.tag)
                            protagonistRefresh.value++
                        }
                        is ReadingMemoryDetailIntent.RemoveTag -> {
                            repository.removeTag(bookUrl, intent.tag)
                            protagonistRefresh.value++
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
                        is ReadingMemoryDetailIntent.OpenBook -> {
                            _effectFlow.tryEmit(ReadingMemoryDetailEffect.OpenReadBook(bookUrl))
                        }
                        is ReadingMemoryDetailIntent.OpenBookInfoEdit -> {
                            _effectFlow.tryEmit(
                                ReadingMemoryDetailEffect.OpenBookInfoEdit(bookUrl),
                            )
                        }
                        is ReadingMemoryDetailIntent.AddProtagonist -> {
                            repository.setProtagonist(bookUrl, intent.name, true)
                            TextChapterLayout.invalidateRegexCache()
                            protagonistRefresh.value++
                        }
                        is ReadingMemoryDetailIntent.RemoveProtagonist -> {
                            repository.setProtagonist(bookUrl, intent.name, false)
                            TextChapterLayout.invalidateRegexCache()
                            protagonistRefresh.value++
                        }
                        is ReadingMemoryDetailIntent.DeleteReview -> {
                            repository.deleteReview(bookUrl)
                            _showReviewEditor.value = false
                        }
                        is ReadingMemoryDetailIntent.EditMarking -> {
                            repository.saveMarking(intent.marking)
                            bookmarkRefresh.value++
                        }
                        is ReadingMemoryDetailIntent.DeleteMarking -> {
                            repository.deleteMarking(intent.id)
                            bookmarkRefresh.value++
                        }
                        is ReadingMemoryDetailIntent.GenerateShareCard -> {
                            generateShareCard()
                        }
                        is ReadingMemoryDetailIntent.GenerateShareCardFromMarking -> {
                            generateShareCardFromMarking(intent.marking)
                        }
                        is ReadingMemoryDetailIntent.GenerateShareCardFromReview -> {
                            _showReviewEditor.value = false
                            generateShareCard()
                        }
                        is ReadingMemoryDetailIntent.DismissShareCard -> {
                            _showShareCard.value = false
                            _shareCardData.value = null
                        }
                    }
                }
                .launchIn(viewModelScope)
        }
    }

    fun onIntent(intent: ReadingMemoryDetailIntent) {
        intentFlow.tryEmit(intent)
    }

    /** 生成分享卡片并就地展示在预览弹窗中 */
    private fun generateShareCard() {
        _showShareCard.value = true
        _shareCardLoading.value = true
        _shareCardData.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val memory = repository.getByBookUrl(bookUrl)
            val book = repository.getBook(bookUrl)
            val data = memory?.let { io.legado.app.help.book.ShareCardDataBuilder.build(it, book) }
            _shareCardData.value = data
            _shareCardLoading.value = false
        }
    }

    /** 从划线笔记生成分享卡片 */
    private fun generateShareCardFromMarking(marking: io.legado.app.data.entities.BookMarking) {
        _showShareCard.value = true
        _shareCardLoading.value = true
        _shareCardData.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val memory = repository.getByBookUrl(bookUrl)
            val book = repository.getBook(bookUrl)
            val data = io.legado.app.help.book.ShareCardDataBuilder.buildFromMarking(marking, memory, book)
            _shareCardData.value = data
            _shareCardLoading.value = false
        }
    }

    /**
     * 书籍总字数是展示用字符串, 常见形如 "123456" / "12.5万字" / "约30万字",
     * 直接 toLongOrNull() 只有纯数字才成功, 其余一律得到 0, 导致「剩余字数」永远算不出来。
     * 这里按「万」单位换算, 并容忍前后缀文字。
     */
    private fun parseTotalWords(raw: String?): Long {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return 0L
        val number = text.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: return 0L
        val scale = if (text.contains("万")) 10000 else 1
        return (number * scale).toLong().coerceAtLeast(0L)
    }
}
