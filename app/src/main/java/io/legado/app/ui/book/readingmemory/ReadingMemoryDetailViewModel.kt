package io.legado.app.ui.book.readingmemory

import androidx.lifecycle.viewModelScope
import io.legado.app.constant.ReadingStatus
import io.legado.app.data.dao.BookDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.entities.readRecord.ReadRecordTimelineDay
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.ui.base.BaseViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 阅读记忆详情页 ViewModel
 *
 * 时间线复用书籍信息页阅读记录；书摘复用有笔记的书签；
 * 阅读状态 / 评分保存在 ReadingMemory 实体中。
 */
class ReadingMemoryDetailViewModel(
    private val repository: ReadingMemoryRepository,
    private val bookDao: BookDao,
    private val bookUrl: String,
) : BaseViewModel() {

    private val _memory = MutableStateFlow<ReadingMemory?>(null)
    val memoryFlow: Flow<ReadingMemory?> = repository.observeMemory(bookUrl)
    val bookFlow: Flow<Book?> = bookDao.flowGetBook(bookUrl)

    private val bookNameAuthor = MutableStateFlow<Pair<String, String>?>(null)

    init {
        viewModelScope.launch {
            repository.observeMemory(bookUrl).collect { _memory.value = it }
        }
        viewModelScope.launch {
            bookDao.flowGetBook(bookUrl).collect { book ->
                bookNameAuthor.value = book?.let { it.name to it.author }
            }
        }
    }

    val timelineDays: StateFlow<List<ReadRecordTimelineDay>> =
        bookNameAuthor.filterNotNull().flatMapLatest { (name, author) ->
            repository.getBookTimelineDays(name, author)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val readTime: StateFlow<Long> =
        bookNameAuthor.filterNotNull().flatMapLatest { (name, author) ->
            repository.getBookReadTime(name, author)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val excerpts: StateFlow<List<Bookmark>> =
        bookNameAuthor.filterNotNull().flatMapLatest { (name, author) ->
            repository.getExcerpts(name, author)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setReadingStatus(status: ReadingStatus) {
        viewModelScope.launch {
            val base = _memory.value ?: defaultMemory(bookFlow.first())
            repository.saveMemory(
                base.copy(
                    readingStatus = status.value,
                    userModifiedReadingStatus = true,
                    lastReadTime = System.currentTimeMillis(),
                    updateTime = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun setRating(rating: Float) {
        viewModelScope.launch {
            val base = _memory.value ?: defaultMemory(bookFlow.first())
            repository.saveMemory(
                base.copy(
                    rating = rating,
                    userModifiedRating = true,
                    updateTime = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun defaultMemory(book: Book?): ReadingMemory {
        val total = book?.totalChapterNum ?: 0
        val progress = if (total > 0) {
            (book?.durChapterIndex ?: 0).toFloat() / total
        } else {
            0f
        }
        return ReadingMemory(
            id = bookUrl,
            bookUrl = bookUrl,
            bookName = book?.name.orEmpty(),
            bookAuthor = book?.author.orEmpty(),
            coverUrl = book?.getDisplayCover(),
            totalChapterNum = total,
            durChapterIndex = book?.durChapterIndex ?: 0,
            durChapterTitle = book?.durChapterTitle,
            durChapterPos = book?.durChapterPos ?: 0,
            progress = progress,
            createTime = System.currentTimeMillis(),
        )
    }
}
