package io.legado.app.ui.book.readingmemory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.repository.ReadingMemoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReadingMemoryViewModel(
    private val repository: ReadingMemoryRepository,
) : ViewModel() {

    private val _intent = MutableSharedFlow<ReadingMemoryIntent>(extraBufferCapacity = 1)
    private val _effect = MutableSharedFlow<ReadingMemoryEffect>(extraBufferCapacity = 1)
    val effect = _effect.asSharedFlow()

    private val _statusFilter = MutableStateFlow(ReadingMemoryStatusFilter.All)
    val statusFilter: StateFlow<ReadingMemoryStatusFilter> = _statusFilter.asStateFlow()

    private val _sortBy = MutableStateFlow(ReadingMemorySortBy.Recent)
    val sortBy: StateFlow<ReadingMemorySortBy> = _sortBy.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val memoryList: StateFlow<List<ReadingMemory>> = repository.observeAll()
        .flatMapLatest { memories ->
            _statusFilter.map { filter ->
                memories.filter { memory ->
                    when (filter) {
                        ReadingMemoryStatusFilter.All -> true
                        ReadingMemoryStatusFilter.ToRead -> memory.progress == 0f
                        ReadingMemoryStatusFilter.Reading -> memory.progress > 0f && memory.progress < 1f
                        ReadingMemoryStatusFilter.Finished -> memory.progress >= 1f
                        ReadingMemoryStatusFilter.Abandoned -> memory.abandoned
                    }
                }
            }
        }
        .combine(_sortBy) { memories, sort ->
            when (sort) {
                ReadingMemorySortBy.Recent -> memories.sortedByDescending { it.updateTime }
                ReadingMemorySortBy.Rating -> memories.sortedByDescending { it.rating }
                ReadingMemorySortBy.ReadDuration -> memories.sortedByDescending { it.statTotalReadTime }
                ReadingMemorySortBy.Name -> memories.sortedBy { it.bookName.lowercase() }
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    init {
        viewModelScope.launch {
            _intent.collectLatest { intent ->
                when (intent) {
                    is ReadingMemoryIntent.Load -> load()
                    is ReadingMemoryIntent.Filter -> _statusFilter.value = intent.filter
                    is ReadingMemoryIntent.Search -> { /* 搜索在 UI 层处理 */ }
                    is ReadingMemoryIntent.Sort -> _sortBy.value = intent.sortBy
                    is ReadingMemoryIntent.Refresh -> load()
                    is ReadingMemoryIntent.ClickBook -> {
                        _effect.emit(ReadingMemoryEffect.NavigateToDetail(intent.bookUrl))
                    }
                }
            }
        }
        load()
    }

    fun onIntent(intent: ReadingMemoryIntent) {
        _intent.tryEmit(intent)
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.ensureAllMemories()
        }
    }
}
