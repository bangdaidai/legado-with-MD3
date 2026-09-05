package io.legado.app.ui.book.readRecord

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.repository.BookRepository
import io.legado.app.data.repository.ReadRecordRepository
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.domain.usecase.readRecord.GetReadRecordOverviewUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

@Stable
data class ReadRecordOverviewUiState(
    val period: ReadPeriod = ReadPeriod.DAY,
    val referenceDate: LocalDate = LocalDate.now(),
    val totalTime: Long = 0,
    val readingDays: Int = 0,
    val totalBooks: Int = 0,
    val textBookCount: Int = 0,
    val audioBookCount: Int = 0,
    val videoBookCount: Int = 0,
    val finishedBooks: Int = 0,
    val abandonedBooks: Int = 0,
    val reviewCount: Int = 0,
    val markingCount: Int = 0,
    val readingBooks: Int = 0,
    val totalWords: Long = 0,
    val dailyTimeData: List<Pair<LocalDate, Long>> = emptyList(),
    val hourlyTimeData: List<Pair<Int, Long>> = emptyList(),
    val topBooks: List<ReadBookRanking> = emptyList(),
    val dailyTopBook: Map<LocalDate, Pair<String, String>> = emptyMap(),
    val allReadTimes: Map<LocalDate, Long> = emptyMap(),
    val allReadCounts: Map<LocalDate, Int> = emptyMap()
)

data class ReadBookRanking(
    val bookName: String,
    val bookAuthor: String,
    val readTime: Long,
    var coverPath: String? = null
)

enum class ReadPeriod {
    DAY, WEEK, MONTH, YEAR, ALL
}

private data class ExtraStats(
    val sessions: List<io.legado.app.data.entities.readRecord.ReadRecordSession>,
    val abandonedCount: Int,
    val reviewCount: Int,
    val markingCount: Int,
)

class ReadRecordOverviewViewModel(
    private val repository: ReadRecordRepository,
    private val bookRepository: BookRepository,
    private val readingMemoryRepository: ReadingMemoryRepository,
    private val getReadRecordOverviewUseCase: GetReadRecordOverviewUseCase
) : ViewModel() {

    private val _period = MutableStateFlow(ReadPeriod.DAY)
    private val _referenceDate = MutableStateFlow(LocalDate.now())

    private val periodAndDate = combine(_period, _referenceDate) { period, refDate ->
        period to refDate
    }

    val uiState: StateFlow<ReadRecordOverviewUiState> = combine(
        periodAndDate,
        repository.getAllRecordDetails(""),
        repository.getLatestReadRecords(""),
        bookRepository.getAllBooks(),
        combine(
            repository.getAllSessions(),
            readingMemoryRepository.observeAbandonedCount(),
            readingMemoryRepository.observeReviewCount(),
            readingMemoryRepository.observeMarkingCount()
        ) { sessions, abandoned, review, marking ->
            ExtraStats(sessions, abandoned, review, marking)
        }
    ) { (period, refDate), details, latestRecords, allBooks, extras ->
        getReadRecordOverviewUseCase(period, refDate, details, latestRecords, allBooks, extras.sessions)
            .copy(
                abandonedBooks = extras.abandonedCount,
                reviewCount = extras.reviewCount,
                markingCount = extras.markingCount,
            )
    }.flowOn(Dispatchers.Default).stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ReadRecordOverviewUiState()
    )

    fun onIntent(intent: ReadRecordOverviewIntent) {
        when (intent) {
            is ReadRecordOverviewIntent.SetPeriod -> setPeriod(intent.period)
            ReadRecordOverviewIntent.NextDate -> nextDate()
            ReadRecordOverviewIntent.PreviousDate -> prevDate()
        }
    }

    fun setPeriod(period: ReadPeriod) {
        _period.value = period
    }

    fun nextDate() {
        val current = _referenceDate.value
        _referenceDate.value = when (_period.value) {
            ReadPeriod.DAY -> current.plusDays(1)
            ReadPeriod.WEEK -> current.plusWeeks(1)
            ReadPeriod.MONTH -> current.plusMonths(1)
            ReadPeriod.YEAR -> current.plusYears(1)
            ReadPeriod.ALL -> current
        }
    }

    fun prevDate() {
        val current = _referenceDate.value
        _referenceDate.value = when (_period.value) {
            ReadPeriod.DAY -> current.minusDays(1)
            ReadPeriod.WEEK -> current.minusWeeks(1)
            ReadPeriod.MONTH -> current.minusMonths(1)
            ReadPeriod.YEAR -> current.minusYears(1)
            ReadPeriod.ALL -> current
        }
    }

    suspend fun getBookCover(name: String, author: String) = bookRepository.getBookCoverByNameAndAuthor(name, author)
}

sealed interface ReadRecordOverviewIntent {
    data class SetPeriod(val period: ReadPeriod) : ReadRecordOverviewIntent
    data object NextDate : ReadRecordOverviewIntent
    data object PreviousDate : ReadRecordOverviewIntent
}

sealed interface ReadRecordOverviewEffect
