package io.legado.app.ui.book.readingmemory

import androidx.lifecycle.viewModelScope
import io.legado.app.constant.ReadingStatus
import io.legado.app.data.dao.BookDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProtagonist
import io.legado.app.data.entities.BookReview
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.help.book.ProtagonistExtractor
import io.legado.app.base.BaseViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * 阅读记忆详情页 ViewModel
 *
 * 对齐 readdai 的 ReadingMemoryDetail：基本信息 + 阅读数据（全维度）+
 * 按月分组的阅读会话 + 书摘 + 书评 + 标签 + 主角。
 *
 * 时间线复用书籍信息页阅读记录；书摘复用有笔记的书签；
 * 主角 / 书评 / 标签 复用各自实体表（与 readdai 同构）。
 */
class ReadingMemoryDetailViewModel(
    private val repository: ReadingMemoryRepository,
    private val bookDao: BookDao,
    private val bookUrl: String,
) : BaseViewModel() {

    val memoryFlow: Flow<ReadingMemory?> = repository.observeMemory(bookUrl)
    val bookFlow: Flow<Book?> = bookDao.flowGetBook(bookUrl)

    private val bookNameAuthor = MutableStateFlow<Pair<String, String>?>(null)

    private val _toast = MutableSharedFlow<String>()
    val toastEvents: Flow<String> = _toast

    init {
        viewModelScope.launch {
            bookDao.flowGetBook(bookUrl).collect { book ->
                if (book != null) bookNameAuthor.emit(book.name to book.author)
            }
        }
    }

    val timelineDays: StateFlow<List<io.legado.app.data.entities.readRecord.ReadRecordTimelineDay>> =
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

    val tags: StateFlow<List<BookTag>> =
        repository.observeTags(bookUrl)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val protagonists: StateFlow<List<BookProtagonist>> =
        repository.observeProtagonists(bookUrl)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val reviews: StateFlow<List<BookReview>> =
        repository.observeReviews(bookUrl)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sessions: StateFlow<List<ReadRecordSession>> =
        bookNameAuthor.filterNotNull().flatMapLatest { (name, author) ->
            repository.observeSessions(name, author)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val readingStats: StateFlow<ReadingStats> = combine(memoryFlow, bookFlow, readTime, sessions) { memory, book, total, sess ->
        computeStats(memory, book, sess, total)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingStats())

    val sessionsByMonth: StateFlow<List<MonthReadingSessions>> =
        sessions.map { groupSessionsByMonth(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val uiState: StateFlow<ReadingMemoryDetailUiState> =
        combine(
            memoryFlow,
            bookFlow,
            readTime,
            sessions,
            tags,
        ) { memory, book, total, sess, tg ->
            ReadingMemoryDetailUiState(
                memory = memory,
                book = book,
                readTime = total,
                sessions = sess,
                stats = computeStats(memory, book, sess, total),
                sessionsByMonth = groupSessionsByMonth(sess),
                tags = tg,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingMemoryDetailUiState())

    // ===== 阅读状态 / 评分 =====
    fun setReadingStatus(status: ReadingStatus) {
        viewModelScope.launch {
            val base = memoryFlow.first() ?: defaultMemory(bookFlow.first())
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
            val base = memoryFlow.first() ?: defaultMemory(bookFlow.first())
            repository.saveMemory(
                base.copy(
                    rating = rating,
                    userModifiedRating = true,
                    updateTime = System.currentTimeMillis(),
                ),
            )
        }
    }

    // ===== 标签 =====
    fun addTag(name: String) {
        viewModelScope.launch {
            repository.addTag(bookUrl, name)
        }
    }

    fun removeTag(tag: BookTag) {
        viewModelScope.launch {
            repository.removeTag(bookUrl, tag)
        }
    }

    // ===== 主角 =====
    fun addProtagonist(name: String, isCustom: Boolean = true) {
        viewModelScope.launch {
            repository.addProtagonist(bookUrl, name, isCustom)
        }
    }

    fun removeProtagonist(id: Long) {
        viewModelScope.launch {
            repository.removeProtagonist(id)
        }
    }

    fun extractProtagonists() {
        viewModelScope.launch {
            val intro = bookFlow.first()?.getDisplayIntro().orEmpty()
            val names = ProtagonistExtractor.extractProtagonists(intro)
            if (names.isEmpty()) {
                _toast.emit("未从简介中提取到主角，可手动添加")
            } else {
                names.forEach { repository.addProtagonist(bookUrl, it, isCustom = false) }
                _toast.emit("已提取 ${names.size} 个主角")
            }
        }
    }

    // ===== 书评 =====
    fun addReview(content: String) {
        viewModelScope.launch {
            val book = bookFlow.first()
            val review = BookReview(
                bookUrl = bookUrl,
                bookName = book?.name.orEmpty(),
                bookAuthor = book?.author.orEmpty(),
                reviewContent = content,
                updateTime = System.currentTimeMillis(),
            )
            repository.addReview(review)
        }
    }

    fun updateReview(review: BookReview, content: String) {
        viewModelScope.launch {
            repository.updateReview(review.copy(reviewContent = content, updateTime = System.currentTimeMillis()))
        }
    }

    fun deleteReview(id: Long) {
        viewModelScope.launch {
            repository.deleteReview(id)
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

    companion object {
        private val MONTH_FORMAT = SimpleDateFormat("yyyy年MM月", Locale.CHINA)
        private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        private val FULL_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        private val TIME_FORMAT = SimpleDateFormat("HH:mm", Locale.CHINA)
        private val WAN_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*万")

        private fun parseWan(text: String?): Double? {
            if (text.isNullOrBlank()) return null
            val m = WAN_PATTERN.matcher(text)
            return if (m.find()) m.group(1).toDoubleOrNull() else null
        }

        private fun formatLastRead(time: Long): String {
            if (time <= 0) return "暂无"
            val now = Calendar.getInstance()
            val today = now.get(Calendar.DAY_OF_YEAR)
            val thisYear = now.get(Calendar.YEAR)
            val cal = Calendar.getInstance().apply { timeInMillis = time }
            val thatDay = cal.get(Calendar.DAY_OF_YEAR)
            val thatYear = cal.get(Calendar.YEAR)
            val timeStr = TIME_FORMAT.format(Date(time))
            return when {
                thatYear == thisYear && thatDay == today -> "今天 $timeStr"
                thatYear == thisYear && thatDay == today - 1 -> "昨天 $timeStr"
                else -> FULL_FORMAT.format(Date(time))
            }
        }

        private fun computeStats(
            memory: ReadingMemory?,
            book: Book?,
            sessions: List<ReadRecordSession>,
            totalReadTime: Long,
        ): ReadingStats {
            val readChapterIndex = memory?.durChapterIndex ?: book?.durChapterIndex ?: 0
            val totalChapterNum = memory?.totalChapterNum ?: book?.totalChapterNum ?: 0
            val progressPercent = (memory?.progress ?: run {
                if (totalChapterNum > 0) readChapterIndex.toFloat() / totalChapterNum else 0f
            }).toInt().coerceIn(0, 100)

            val dayToTime = HashMap<String, Long>()
            var maxDayTime = 0L
            var maxDayDate = 0L
            var totalWords = 0L
            var lastTime = 0L
            var firstTime = 0L
            for (s in sessions) {
                val dur = (s.endTime - s.startTime).coerceAtLeast(0L)
                totalWords += s.words
                if (s.endTime > lastTime) lastTime = s.endTime
                if (firstTime == 0L || s.startTime < firstTime) firstTime = s.startTime
                val dayKey = DAY_FORMAT.format(Date(s.startTime))
                val cur = dayToTime.getOrDefault(dayKey, 0L) + dur
                dayToTime[dayKey] = cur
                if (cur > maxDayTime) {
                    maxDayTime = cur
                    maxDayDate = s.startTime
                }
            }

            val totalWan = parseWan(memory?.wordCount)
            val readWan = totalWords / 10000.0
            val remainingWan = totalWan?.let { (it - readWan).coerceAtLeast(0.0) }

            return ReadingStats(
                totalReadTime = totalReadTime,
                readingDays = dayToTime.size,
                readChapterIndex = readChapterIndex,
                totalChapterNum = totalChapterNum,
                maxDayReadTime = maxDayTime,
                maxDayReadDate = maxDayDate,
                totalReadWordsWan = if (totalWords > 0) readWan else null,
                remainingWordsWan = remainingWan,
                lastReadTime = lastTime,
                firstReadTime = firstTime,
                wordCountText = memory?.wordCount ?: "",
                kindText = memory?.kind ?: "未知",
                lastReadText = formatLastRead(lastTime),
                firstReadText = if (firstTime > 0) "始于 ${FULL_FORMAT.format(Date(firstTime))}" else "暂无",
                progressPercent = progressPercent,
            )
        }

        private fun groupSessionsByMonth(sessions: List<ReadRecordSession>): List<MonthReadingSessions> {
            if (sessions.isEmpty()) return emptyList()
            val byMonth = LinkedHashMap<String, MutableList<ReadRecordSession>>()
            for (s in sessions) {
                val key = MONTH_FORMAT.format(Date(s.startTime))
                byMonth.getOrPut(key) { mutableListOf() }.add(s)
            }
            return byMonth.map { (title, list) ->
                val dayMap = LinkedHashMap<Long, Long>()
                var monthTotal = 0L
                for (s in list) {
                    val dur = (s.endTime - s.startTime).coerceAtLeast(0L)
                    monthTotal += dur
                    val dayStart = DAY_FORMAT.parse(DAY_FORMAT.format(Date(s.startTime)))?.time ?: s.startTime
                    dayMap[dayStart] = (dayMap[dayStart] ?: 0L) + dur
                }
                MonthReadingSessions(
                    monthTitle = title,
                    totalTime = monthTotal,
                    days = dayMap.map { (date, time) -> DayReadItem(date, time) }
                        .sortedByDescending { it.date },
                )
            }.reversed()
        }
    }
}

data class ReadingMemoryDetailUiState(
    val memory: ReadingMemory? = null,
    val book: Book? = null,
    val readTime: Long = 0L,
    val sessions: List<ReadRecordSession> = emptyList(),
    val stats: ReadingStats = ReadingStats(),
    val sessionsByMonth: List<MonthReadingSessions> = emptyList(),
    val tags: List<BookTag> = emptyList(),
    val protagonists: List<BookProtagonist> = emptyList(),
    val reviews: List<BookReview> = emptyList(),
    val excerpts: List<Bookmark> = emptyList(),
)

data class ReadingStats(
    val totalReadTime: Long = 0L,
    val readingDays: Int = 0,
    val readChapterIndex: Int = 0,
    val totalChapterNum: Int = 0,
    val maxDayReadTime: Long = 0L,
    val maxDayReadDate: Long = 0L,
    val totalReadWordsWan: Double? = null,
    val remainingWordsWan: Double? = null,
    val lastReadTime: Long = 0L,
    val firstReadTime: Long = 0L,
    val wordCountText: String = "",
    val kindText: String = "",
    val lastReadText: String = "",
    val firstReadText: String = "",
    val progressPercent: Int = 0,
)

data class MonthReadingSessions(
    val monthTitle: String,
    val totalTime: Long,
    val days: List<DayReadItem>,
)

data class DayReadItem(
    val date: Long,
    val time: Long,
)
