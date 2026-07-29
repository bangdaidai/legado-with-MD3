package io.legado.app.ui.book.readingmemory

import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.help.book.TagManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import java.util.Calendar

class ReadingMemoryViewModel(
    private val repository: ReadingMemoryRepository,
) : ViewModel() {

    private val _intent = MutableSharedFlow<ReadingMemoryIntent>(extraBufferCapacity = 1)
    private val _effect = MutableSharedFlow<ReadingMemoryEffect>(extraBufferCapacity = 1)
    val effect = _effect.asSharedFlow()

    private val _statusFilter = MutableStateFlow(ReadingMemoryStatusFilter.All)
    private val _ratingFilter = MutableStateFlow(ReadingMemoryRatingFilter.All)
    private val _readTypeFilter = MutableStateFlow(ReadingMemoryReadTypeFilter.All)
    private val _onlyWithReview = MutableStateFlow(false)
    private val _groupBy = MutableStateFlow(ReadingMemoryGroupBy.None)
    private val _sortBy = MutableStateFlow(ReadingMemorySortBy.Recent)
    private val _showCard = MutableStateFlow(true)
    private val _showIntro = MutableStateFlow(true)
    private val _showReview = MutableStateFlow(false)
    private val _searchQuery = MutableStateFlow("")
    private val _collapsedGroups = MutableStateFlow<Set<String>>(emptySet())
    private val _loading = MutableStateFlow(false)

    val statusFilter = _statusFilter.asStateFlow()
    val ratingFilter = _ratingFilter.asStateFlow()
    val readTypeFilter = _readTypeFilter.asStateFlow()
    val onlyWithReview = _onlyWithReview.asStateFlow()
    val groupBy = _groupBy.asStateFlow()
    val sortBy = _sortBy.asStateFlow()
    val showCard = _showCard.asStateFlow()
    val showIntro = _showIntro.asStateFlow()
    val showReview = _showReview.asStateFlow()
    val searchQuery = _searchQuery.asStateFlow()

    private data class Controls(
        val status: ReadingMemoryStatusFilter,
        val rating: ReadingMemoryRatingFilter,
        val type: ReadingMemoryReadTypeFilter,
        val onlyWithReview: Boolean,
        val groupBy: ReadingMemoryGroupBy,
        val sortBy: ReadingMemorySortBy,
        val showCard: Boolean,
        val showIntro: Boolean,
        val showReview: Boolean,
        val search: String,
        val collapsed: Set<String>,
    )

    private val controls: StateFlow<Controls> = combine(
        _statusFilter, _ratingFilter, _readTypeFilter, _onlyWithReview,
        _groupBy, _sortBy, _showCard, _showIntro, _showReview, _searchQuery, _collapsedGroups
    ) { a ->
        Controls(
            status = a[0] as ReadingMemoryStatusFilter,
            rating = a[1] as ReadingMemoryRatingFilter,
            type = a[2] as ReadingMemoryReadTypeFilter,
            onlyWithReview = a[3] as Boolean,
            groupBy = a[4] as ReadingMemoryGroupBy,
            sortBy = a[5] as ReadingMemorySortBy,
            showCard = a[6] as Boolean,
            showIntro = a[7] as Boolean,
            showReview = a[8] as Boolean,
            search = a[9] as String,
            collapsed = a[10] as Set<String>,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        Controls(
            ReadingMemoryStatusFilter.All, ReadingMemoryRatingFilter.All, ReadingMemoryReadTypeFilter.All,
            false, ReadingMemoryGroupBy.None, ReadingMemorySortBy.Recent,
            true, true, false, "", emptySet(),
        ),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ReadingMemoryUiState> = repository.observeAll()
        .flatMapLatest { memories -> controls.map { buildUiState(memories, it) } }
        .flowOn(Dispatchers.IO)
        .combine(_loading) { state, loading -> state.copy(loading = loading) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            ReadingMemoryUiState(),
        )

    init {
        viewModelScope.launch {
            _intent.collect { intent ->
                when (intent) {
                    is ReadingMemoryIntent.Load -> load()
                    is ReadingMemoryIntent.Refresh -> load()
                    is ReadingMemoryIntent.FilterStatus -> _statusFilter.value = intent.filter
                    is ReadingMemoryIntent.SetRatingFilter -> _ratingFilter.value = intent.filter
                    is ReadingMemoryIntent.SetReadTypeFilter -> _readTypeFilter.value = intent.filter
                    is ReadingMemoryIntent.ToggleOnlyWithReview -> _onlyWithReview.value = intent.value
                    is ReadingMemoryIntent.SetGroupBy -> {
                        _groupBy.value = intent.groupBy
                        _collapsedGroups.value = emptySet()
                    }
                    is ReadingMemoryIntent.ToggleGroupCollapse -> {
                        _collapsedGroups.value = _collapsedGroups.value.toMutableSet().apply {
                            if (contains(intent.key)) remove(intent.key) else add(intent.key)
                        }
                    }
                    is ReadingMemoryIntent.SetSortBy -> _sortBy.value = intent.sortBy
                    is ReadingMemoryIntent.ToggleShowCard -> _showCard.value = intent.value
                    is ReadingMemoryIntent.ToggleShowIntro -> _showIntro.value = intent.value
                    is ReadingMemoryIntent.ToggleShowReview -> _showReview.value = intent.value
                    is ReadingMemoryIntent.Search -> _searchQuery.value = intent.query
                    is ReadingMemoryIntent.ClickBook ->
                        _effect.emit(ReadingMemoryEffect.NavigateToDetail(intent.bookUrl))
                    is ReadingMemoryIntent.SetAbandoned -> runEdit { repository.markAbandoned(intent.bookUrl) }
                    is ReadingMemoryIntent.RemoveAbandoned -> runEdit { repository.unmarkAbandoned(intent.bookUrl) }
                    is ReadingMemoryIntent.DeleteMemory -> runEdit { repository.deleteMemory(intent.bookUrl) }
                    is ReadingMemoryIntent.ClearAll -> runEdit { repository.clearAll() }
                }
            }
        }
        // 与书籍信息页一致：外部标签改名（TAGS_UPDATED）时即时刷新阅读记忆
        viewModelScope.launch {
            eventFlow<String>(EventBus.TAGS_UPDATED).collect { load() }
        }
        load()
    }

    private inline fun <reified T> eventFlow(tag: String): Flow<T> = callbackFlow {
        val observer = Observer<T> { trySend(it) }
        LiveEventBus.get<T>(tag).observeForever(observer)
        awaitClose {
            LiveEventBus.get<T>(tag).removeObserver(observer)
        }
    }

    fun onIntent(intent: ReadingMemoryIntent) {
        _intent.tryEmit(intent)
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            try {
                repository.ensureAllMemories()
            } finally {
                _loading.value = false
            }
        }
    }

    private fun runEdit(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }

    private suspend fun buildUiState(memories: List<ReadingMemory>, c: Controls): ReadingMemoryUiState {
        val keyword = c.search.trim().lowercase()

        val statusOk: (ReadingMemory) -> Boolean = { mem ->
            when (c.status) {
                ReadingMemoryStatusFilter.All -> true
                ReadingMemoryStatusFilter.Abandoned -> mem.abandoned
                ReadingMemoryStatusFilter.ToRead -> !mem.abandoned && mem.progress == 0f
                ReadingMemoryStatusFilter.Reading ->
                    !mem.abandoned && mem.progress > 0f && mem.progress < 1f
                ReadingMemoryStatusFilter.Finished -> !mem.abandoned && mem.progress >= 1f
            }
        }

        var list = memories.filter(statusOk)

        list = when (c.rating) {
            ReadingMemoryRatingFilter.All -> list
            ReadingMemoryRatingFilter.Unrated -> list.filter { it.rating == 0f }
            ReadingMemoryRatingFilter.R5 -> list.filter { it.rating >= 5f }
            ReadingMemoryRatingFilter.R4 -> list.filter { it.rating >= 4f && it.rating < 5f }
            ReadingMemoryRatingFilter.R3 -> list.filter { it.rating >= 3f && it.rating < 4f }
            ReadingMemoryRatingFilter.R2 -> list.filter { it.rating >= 2f && it.rating < 3f }
            ReadingMemoryRatingFilter.R1 -> list.filter { it.rating >= 1f && it.rating < 2f }
        }

        list = when (c.type) {
            ReadingMemoryReadTypeFilter.All -> list
            else -> {
                val mask = when (c.type) {
                    ReadingMemoryReadTypeFilter.Text -> BookType.text
                    ReadingMemoryReadTypeFilter.Audio -> BookType.audio
                    ReadingMemoryReadTypeFilter.Video -> BookType.video
                    else -> 0
                }
                list.filter { mem ->
                    val t = if (mem.type == 0) BookType.text else mem.type
                    (t and mask) > 0
                }
            }
        }

        if (c.onlyWithReview) {
            list = list.filter { !it.review.isNullOrBlank() }
        }

        if (keyword.isNotEmpty()) {
            list = list.filter { mem ->
                mem.bookName.lowercase().contains(keyword) ||
                    mem.bookAuthor.lowercase().contains(keyword) ||
                    (mem.kind?.lowercase()?.contains(keyword) ?: false)
            }
        }

        val sorted = when (c.sortBy) {
            ReadingMemorySortBy.Recent -> list.sortedByDescending { if (it.lastReadTime > 0) it.lastReadTime else it.createTime }
            ReadingMemorySortBy.Rating -> list.sortedByDescending { it.rating }
            ReadingMemorySortBy.ReadDuration -> list.sortedByDescending { it.statTotalReadTime }
            ReadingMemorySortBy.Name -> list.sortedBy { it.bookName.lowercase() }
        }

        val customTagMap = repository.getCustomTagMap()

        val items = if (c.groupBy == ReadingMemoryGroupBy.None) {
            sorted.map { ReadingMemoryListItem.BookItem(it, TagManager.bookDisplayTags(it.kind, customTagMap[it.bookUrl])) }
        } else {
            val grouped = when (c.groupBy) {
                ReadingMemoryGroupBy.Year -> groupAndOrder(
                    sorted, ::yearKey,
                ) { a, b ->
                    if (a == "未知年份") 1 else if (b == "未知年份") -1 else b.compareTo(a)
                }
                ReadingMemoryGroupBy.Rating -> groupAndOrder(
                    sorted, ::ratingKey,
                ) { a, b -> ratingOrder.indexOf(a).compareTo(ratingOrder.indexOf(b)) }
                ReadingMemoryGroupBy.Status -> groupAndOrder(
                    sorted, ::statusKey,
                ) { a, b -> statusOrder.indexOf(a).compareTo(statusOrder.indexOf(b)) }
                else -> linkedMapOf<String, List<ReadingMemory>>()
            }
            buildList<ReadingMemoryListItem> {
                grouped.forEach { (key, mems) ->
                    val display = groupDisplay(key, c.groupBy)
                    val collapsed = c.collapsed.contains(key)
                    add(ReadingMemoryListItem.GroupHeader(key, display, mems.size, collapsed))
                    if (!collapsed) mems.forEach { add(ReadingMemoryListItem.BookItem(it, TagManager.bookDisplayTags(it.kind, customTagMap[it.bookUrl]))) }
                }
            }
        }

        return ReadingMemoryUiState(
            items = items,
            statusFilter = c.status,
            ratingFilter = c.rating,
            readTypeFilter = c.type,
            onlyWithReview = c.onlyWithReview,
            groupBy = c.groupBy,
            sortBy = c.sortBy,
            showCard = c.showCard,
            showIntro = c.showIntro,
            showReview = c.showReview,
            searchQuery = c.search,
        )
    }

    private fun groupAndOrder(
        list: List<ReadingMemory>,
        keyOf: (ReadingMemory) -> String,
        order: (String, String) -> Int,
    ): LinkedHashMap<String, List<ReadingMemory>> {
        val map = LinkedHashMap<String, MutableList<ReadingMemory>>()
        list.forEach { mem -> map.getOrPut(keyOf(mem)) { mutableListOf() }.add(mem) }
        val sortedKeys = map.keys.sortedWith(order)
        val result = LinkedHashMap<String, List<ReadingMemory>>()
        sortedKeys.forEach { result[it] = map[it]!! }
        return result
    }

    private val ratingOrder = listOf("5", "4", "3", "2", "1", "0")
    private val statusOrder = listOf("reading", "toread", "finished", "abandoned")

    private fun yearKey(m: ReadingMemory): String {
        val t = if (m.firstReadTime > 0) m.firstReadTime else m.createTime
        if (t <= 0) return "未知年份"
        val cal = Calendar.getInstance().apply { timeInMillis = t }
        return cal.get(Calendar.YEAR).toString()
    }

    private fun ratingKey(m: ReadingMemory): String = when {
        m.rating >= 5f -> "5"
        m.rating >= 4f -> "4"
        m.rating >= 3f -> "3"
        m.rating >= 2f -> "2"
        m.rating >= 1f -> "1"
        else -> "0"
    }

    private fun statusKey(m: ReadingMemory): String = when {
        m.abandoned -> "abandoned"
        m.progress >= 1f -> "finished"
        m.progress > 0f -> "reading"
        else -> "toread"
    }

    private fun groupDisplay(key: String, groupBy: ReadingMemoryGroupBy): String = when (groupBy) {
        ReadingMemoryGroupBy.Year -> "${key}年"
        ReadingMemoryGroupBy.Rating -> "${stars(key.toIntOrNull() ?: 0)} ($key 星)"
        ReadingMemoryGroupBy.Status -> when (key) {
            "reading" -> "在读"
            "toread" -> "未读"
            "finished" -> "已读"
            "abandoned" -> "弃文"
            else -> key
        }
        else -> key
    }

    private fun stars(n: Int): String {
        val v = n.coerceIn(0, 5)
        return "★".repeat(v) + "☆".repeat(5 - v)
    }
}
