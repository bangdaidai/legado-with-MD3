package io.legado.app.ui.main.my.authorManage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.repository.ReadingMemoryRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class AuthorManageViewModel(
    private val repository: ReadingMemoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthorManageUiState())
    val uiState: StateFlow<AuthorManageUiState> = _uiState.asStateFlow()

    private val _sortBy = MutableStateFlow(AuthorSort.BookCount)
    private val _searchQuery = MutableStateFlow("")

    init {
        viewModelScope.launch {
            combine(
                listOf<Flow<Any?>>(
                    repository.observeAll(),
                    AuthorProfileStore.observeBios(),
                    _sortBy,
                    _searchQuery,
                )
            ) { a ->
                val memories = a[0] as List<ReadingMemory>
                val bios = a[1] as Map<String, String>
                val sortBy = a[2] as AuthorSort
                val searchQuery = a[3] as String
                buildState(memories, bios, sortBy, searchQuery)
            }.collect { _uiState.value = it }
        }
    }

    fun onIntent(intent: AuthorManageIntent) {
        when (intent) {
            is AuthorManageIntent.SetSort -> _sortBy.value = intent.sort
            is AuthorManageIntent.SetSearchQuery -> _searchQuery.value = intent.query
        }
    }

    private suspend fun buildState(
        memories: List<ReadingMemory>,
        bios: Map<String, String>,
        sortBy: AuthorSort,
        searchQuery: String,
    ): AuthorManageUiState {
        val allAuthors = buildAuthors(memories, bios, sortBy)
        val query = searchQuery.trim()
        val authors = if (query.isBlank()) {
            allAuthors
        } else {
            allAuthors.filter { it.name.contains(query, ignoreCase = true) }.toImmutableList()
        }
        return AuthorManageUiState(
            authors = authors,
            sortBy = sortBy,
            searchQuery = searchQuery,
        )
    }

    private fun buildAuthors(
        memories: List<ReadingMemory>,
        bios: Map<String, String>,
        sortBy: AuthorSort,
    ): ImmutableList<AuthorItemUi> {
        val byAuthor = memories.filter { it.bookAuthor.isNotBlank() }
            .groupBy { it.bookAuthor }
        val list = byAuthor.map { (name, mems) ->
            val finished = mems.filter { isAuthorBookFinished(it) }
            AuthorItemUi(
                name = name,
                bookCount = mems.size,
                readBookCount = finished.size,
                avgRating = authorAvgRating(finished),
                bio = bios[name] ?: "",
            )
        }
        val sorted = when (sortBy) {
            AuthorSort.BookCount -> list.sortedByDescending { it.bookCount }
            AuthorSort.Rating -> list.sortedByDescending { it.avgRating }
        }
        return sorted.toImmutableList()
    }
}
