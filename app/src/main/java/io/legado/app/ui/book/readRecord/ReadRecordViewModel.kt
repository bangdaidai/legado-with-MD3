package io.legado.app.ui.book.readRecord

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.data.entities.readRecord.ReadRecordRepairReport
import io.legado.app.data.appDb
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import io.legado.app.data.repository.SettingsRepository
import io.legado.app.data.repository.BookRepository
import io.legado.app.data.repository.ReadRecordRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Stable
data class ReadRecordUiState(
    val isLoading: Boolean = true,
    val totalReadTime: Long = 0,
    val groupedRecords: ImmutableMap<String, ImmutableList<ReadRecordDetail>> = persistentMapOf(),
    val timelineRecords: ImmutableMap<String, ImmutableList<ReadRecordSession>> = persistentMapOf(),
    val latestRecords: ImmutableList<ReadRecord> = persistentListOf(),
    val durationRecords: ImmutableList<ReadRecord> = persistentListOf(),
    val selectedDate: LocalDate? = null,
    val searchKey: String? = null,
    val dailyReadCounts: ImmutableMap<LocalDate, Int> = persistentMapOf(),
    val dailyReadTimes: ImmutableMap<LocalDate, Long> = persistentMapOf(),
    val displayMode: DisplayMode = DisplayMode.AGGREGATE,
    val readRecordEnabled: Boolean = true,
    val bookTypeFilter: Int? = null,
    val repairReport: ReadRecordRepairReport? = null,
)

enum class DisplayMode {
    AGGREGATE,
    TIMELINE,
    LATEST,
    DURATION
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReadRecordViewModel(
    private val repository: ReadRecordRepository,
    private val bookRepository: BookRepository,
    private val localPreferencesRepository: SettingsRepository
) : ViewModel() {

    private val _displayMode = MutableStateFlow(DisplayMode.AGGREGATE)
    val displayMode = _displayMode.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = localPreferencesRepository.getPreference(
                LocalPreferencesKeys.READ_RECORD_DISPLAY_MODE, DisplayMode.AGGREGATE.name
            ).first()
            _displayMode.value = runCatching { DisplayMode.valueOf(saved) }
                .getOrDefault(DisplayMode.AGGREGATE)
        }
    }

    private val _searchKey = MutableStateFlow("")
    private val _repairReport = MutableStateFlow<ReadRecordRepairReport?>(null)
    private val _effects = MutableSharedFlow<ReadRecordEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()
    private val _selectedDate = MutableStateFlow<LocalDate?>(null)
    private val _bookTypeFilter = MutableStateFlow<Int?>(null)
    val readRecordEnabled: StateFlow<Boolean> = repository.readRecordEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val loadedDataFlow = combine(_searchKey, _bookTypeFilter) { q, t -> q to t }
        .flatMapLatest { (query, bookType) ->
            combine(
                repository.getAllRecordDetails(query, bookType),
                repository.getLatestReadRecords(query, bookType),
                repository.getAllSessions(bookType),
                repository.getTotalReadTime(bookType)
            ) { details, latest, sessions, totalTime ->
                LoadedData(totalTime, details, latest, sessions)
            }
        }

    val uiState: StateFlow<ReadRecordUiState> = combine(
        combine(loadedDataFlow, _bookTypeFilter) { d, t -> d to t },
        _selectedDate,
        _searchKey,
        _displayMode,
        readRecordEnabled,
    ) { (data, bookTypeFilter), selectedDate, searchKey, displayMode, enabled ->
        val dateStr = selectedDate?.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val dailyCounts = data.details
            .groupBy { it.date }
            .mapKeys { LocalDate.parse(it.key, DateTimeFormatter.ISO_LOCAL_DATE) }
            .mapValues { it.value.size }

        val dailyTimes = data.sessions
            .groupBy { it.startTime.toDateString() }
            .mapKeys { LocalDate.parse(it.key, DateTimeFormatter.ISO_LOCAL_DATE) }
            .mapValues { (_, sessions) ->
                sessions.sumOf { (it.endTime - it.startTime).coerceAtLeast(0L) }
            }

        val filteredDetails = data.details.filter { detail ->
            dateStr == null || detail.date == dateStr
        }

        val timelineMap = data.sessions
            .asSequence()
            .filter { session ->
                val sDate = session.startTime.toDateString()
                (dateStr == null || sDate == dateStr) &&
                        (searchKey.isEmpty() ||
                                session.bookName.contains(searchKey, ignoreCase = true) ||
                                session.bookAuthor.contains(searchKey, ignoreCase = true))
            }
            .groupBy { it.startTime.toDateString() }
            .mapValues { (_, sessions) ->
                mergeContinuousSessions(sessions).reversed()
            }
            .toSortedMap(compareByDescending { it })

        val latestRecords = fillMissingAuthors(data.latestRecords)

        ReadRecordUiState(
            isLoading = false,
            totalReadTime = data.totalReadTime,
            groupedRecords = filteredDetails.groupBy { it.date }
                .mapValues { (_, value) -> value.toImmutableList() }
                .toImmutableMap(),
            timelineRecords = timelineMap
                .mapValues { (_, value) -> value.toImmutableList() }
                .toImmutableMap(),
            latestRecords = latestRecords.toImmutableList(),
            durationRecords = latestRecords.sortedByDescending { it.readTime }.toImmutableList(),
            selectedDate = selectedDate,
            searchKey = searchKey,
            dailyReadCounts = dailyCounts.toImmutableMap(),
            dailyReadTimes = dailyTimes.toImmutableMap(),
            displayMode = displayMode,
            readRecordEnabled = enabled,
            bookTypeFilter = bookTypeFilter,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ReadRecordUiState(isLoading = true)
    ).combine(_repairReport) { state, report -> state.copy(repairReport = report) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ReadRecordUiState(isLoading = true),
        )

    fun onIntent(intent: ReadRecordIntent) {
        when (intent) {
            is ReadRecordIntent.Search -> setSearchKey(intent.query)
            is ReadRecordIntent.SetDisplayMode -> setDisplayMode(intent.mode)
            is ReadRecordIntent.SelectDate -> setSelectedDate(intent.date)
            is ReadRecordIntent.DeleteDetail -> deleteDetail(intent.detail)
            is ReadRecordIntent.DeleteSession -> deleteSession(intent.session)
            is ReadRecordIntent.DeleteRecord -> deleteReadRecord(intent.record)
            ReadRecordIntent.ClearRecords -> clearReadRecords()
            is ReadRecordIntent.SetEnabled -> setReadRecordEnabled(intent.enabled)
            is ReadRecordIntent.MergeRecords -> mergeReadRecords(intent.target, intent.sources)
            is ReadRecordIntent.SetBookTypeFilter -> setBookTypeFilter(intent.bookType)
            ReadRecordIntent.ScanRepair -> scanRepair()
            ReadRecordIntent.RepairDatabase -> repairDatabase()
            ReadRecordIntent.DismissRepairReport -> _repairReport.value = null
        }
    }

    fun setSearchKey(query: String) {
        _searchKey.value = query
    }

    fun setBookTypeFilter(bookType: Int?) {
        _bookTypeFilter.value = bookType
    }

    /** 执行只读问题扫描，并将结果放入统一 UiState。 */
    private fun scanRepair() {
        viewModelScope.launch {
            runCatching { repository.scanReadRecordIssues() }
                .onSuccess { _repairReport.value = it }
                .onFailure { _effects.tryEmit(ReadRecordEffect.ShowError(it.localizedMessage.orEmpty())) }
        }
    }

    /** 在事务中修复身份碰撞和字段完全相同的阅读时段记录。 */
    private fun repairDatabase() {
        viewModelScope.launch {
            runCatching {
                val identity = repository.repairReadRecordIdentities()
                val sessions = repository.repairDuplicateSessions()
                identity.copy(duplicateSessionCount = sessions)
            }.onSuccess { _repairReport.value = it }
                .onFailure { _effects.tryEmit(ReadRecordEffect.ShowError(it.localizedMessage.orEmpty())) }
        }
    }

    fun setDisplayMode(mode: DisplayMode) {
        _displayMode.value = mode
        viewModelScope.launch {
            localPreferencesRepository.updatePreference(
                LocalPreferencesKeys.READ_RECORD_DISPLAY_MODE, mode.name
            )
        }
    }

    fun setSelectedDate(date: LocalDate?) {
        _selectedDate.value = date
    }

    fun deleteDetail(detail: ReadRecordDetail) {
        viewModelScope.launch { repository.deleteDetail(detail) }
    }

    fun deleteSession(session: ReadRecordSession) {
        viewModelScope.launch { repository.deleteSession(session) }
    }

    fun deleteReadRecord(record: ReadRecord) {
        viewModelScope.launch { repository.deleteReadRecord(record) }
    }

    fun clearReadRecords() {
        viewModelScope.launch { repository.clearReadRecords() }
    }

    fun setReadRecordEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setReadRecordEnabled(enabled) }
    }

    private fun mergeContinuousSessions(sessions: List<ReadRecordSession>): List<ReadRecordSession> {
        if (sessions.isEmpty()) return emptyList()
        val mergedList = mutableListOf<ReadRecordSession>()
        mergedList.add(sessions.first().copy())

        val gapLimit = 5 * 60 * 1000L

        for (i in 1 until sessions.size) {
            val current = sessions[i]
            val last = mergedList.last()
            if (current.bookName == last.bookName &&
                current.bookAuthor == last.bookAuthor &&
                (current.startTime - last.endTime) <= gapLimit
            ) {
                mergedList[mergedList.lastIndex] = last.copy(
                    endTime = maxOf(last.endTime, current.endTime),
                    words = last.words + current.words
                )
            } else {
                mergedList.add(current.copy())
            }
        }
        return mergedList
    }

    /**
     * 旧记录的 bookAuthor 可能为空(当年书源未返回作者), 展示时按书名从 books 表兜底补齐.
     * 补齐后可能出现与已有记录相同身份键(deviceId, bookName, bookAuthor)的重复行,
     * 需合并以避免 LazyColumn 重复 key 崩溃, 同时修正同书重复展示.
     */
    private suspend fun fillMissingAuthors(records: List<ReadRecord>): List<ReadRecord> {
        if (records.none { it.bookAuthor.isBlank() }) return records
        val resolved = mutableMapOf<String, String?>()
        val patched = records.map { record ->
            if (record.bookAuthor.isNotBlank()) return@map record
            val author = resolved.getOrPut(record.bookName) {
                bookRepository.getBookAuthorByName(record.bookName)
            }
            if (author.isNullOrBlank()) record else record.copy(bookAuthor = author)
        }
        val hasDuplicate = patched
            .groupingBy { Triple(it.deviceId, it.bookName, it.bookAuthor) }
            .eachCount()
            .any { it.value > 1 }
        if (!hasDuplicate) return patched
        return patched
            .groupBy { Triple(it.deviceId, it.bookName, it.bookAuthor) }
            .map { (_, group) ->
                if (group.size == 1) group.first()
                else group.reduce { acc, r ->
                    acc.copy(
                        readTime = acc.readTime + r.readTime,
                        lastRead = maxOf(acc.lastRead, r.lastRead)
                    )
                }
            }
            .sortedByDescending { it.lastRead }
    }

    suspend fun getChapterTitle(bookName: String, bookAuthor: String, chapterIndexLong: Long): String? {
        return bookRepository.getChapterTitle(bookName, bookAuthor, chapterIndexLong.toInt())
    }

    suspend fun getBookCover(bookName: String, bookAuthor: String): String? {
        // 优先从书架获取封面
        val bookCover = bookRepository.getBookCoverByNameAndAuthor(bookName, bookAuthor)
        if (!bookCover.isNullOrEmpty()) return bookCover
        // 书架无封面时，从阅读记录获取封面（影视等不在书架的记录）
        return withContext(Dispatchers.IO) {
            appDb.readRecordDao.getReadRecordByNameAndAuthor(bookName, bookAuthor)?.coverUrl
                ?.takeIf { it.isNotBlank() }
        }
    }

    suspend fun getMergeCandidates(targetRecord: ReadRecord): List<ReadRecord> {
        return repository.getMergeCandidates(targetRecord)
    }

    fun mergeReadRecords(targetRecord: ReadRecord, sourceRecords: List<ReadRecord>) {
        if (sourceRecords.isEmpty()) {
            _effects.tryEmit(ReadRecordEffect.ShowError(""))
            return
        }
        viewModelScope.launch {
            val merged = repository.mergeIndependentReadRecordsInto(targetRecord, sourceRecords)
            if (!merged) {
                _effects.tryEmit(ReadRecordEffect.ShowError(""))
            }
        }
    }

    private data class LoadedData(
        val totalReadTime: Long,
        val details: List<ReadRecordDetail>,
        val latestRecords: List<ReadRecord>,
        val sessions: List<ReadRecordSession>
    )

    private fun Long.toDateString(): String =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate().toString()
}

sealed interface ReadRecordIntent {
    data class Search(val query: String) : ReadRecordIntent
    data class SetDisplayMode(val mode: DisplayMode) : ReadRecordIntent
    data class SelectDate(val date: LocalDate?) : ReadRecordIntent
    data class DeleteDetail(val detail: ReadRecordDetail) : ReadRecordIntent
    data class DeleteSession(val session: ReadRecordSession) : ReadRecordIntent
    data class DeleteRecord(val record: ReadRecord) : ReadRecordIntent
    data object ClearRecords : ReadRecordIntent
    data class SetEnabled(val enabled: Boolean) : ReadRecordIntent
    data class MergeRecords(val target: ReadRecord, val sources: List<ReadRecord>) : ReadRecordIntent
    data class SetBookTypeFilter(val bookType: Int?) : ReadRecordIntent
    data object ScanRepair : ReadRecordIntent
    data object RepairDatabase : ReadRecordIntent
    data object DismissRepairReport : ReadRecordIntent
}

sealed interface ReadRecordEffect {
    data class ShowError(val message: String) : ReadRecordEffect
}
