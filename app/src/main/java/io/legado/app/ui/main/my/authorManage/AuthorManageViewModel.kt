package io.legado.app.ui.main.my.authorManage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.AuthorProfile
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.repository.AuthorProfileRepository
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.utils.cnCompare
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** 已排序的作者列表与其排序方式，捆在一起避免出现"排序方式已变、列表还没重排"的中间态。 */
private data class SortedAuthors(
    val sortBy: AuthorSort,
    val items: ImmutableList<AuthorItemUi>,
)

@OptIn(FlowPreview::class)
class AuthorManageViewModel(
    private val repository: ReadingMemoryRepository,
    private val authorProfileRepository: AuthorProfileRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthorManageUiState(loading = true))
    val uiState: StateFlow<AuthorManageUiState> = _uiState.asStateFlow()

    private val _sortBy = MutableStateFlow(AuthorSort.BookCount)
    private val _searchQuery = MutableStateFlow("")

    /** 分组与排序只跟随数据和排序方式，搜索单独过滤，避免每敲一个字符就重算全表。 */
    private val sortedAuthors: Flow<SortedAuthors> = combine(
        repository.observeAll(),
        authorProfileRepository.observeProfiles(),
        _sortBy,
    ) { memories, profiles, sortBy ->
        SortedAuthors(sortBy, buildAuthors(memories, profiles, sortBy))
    }

    init {
        viewModelScope.launch {
            combine(
                sortedAuthors,
                _searchQuery.debounce { if (it.isBlank()) 0L else 150L },
            ) { sorted, query ->
                AuthorManageUiState(
                    authors = filterAuthors(sorted.items, query),
                    sortBy = sorted.sortBy,
                    searchQuery = query,
                )
            }.flowOn(Dispatchers.Default).collect { _uiState.value = it }
        }
    }

    fun onIntent(intent: AuthorManageIntent) {
        when (intent) {
            is AuthorManageIntent.SetSort -> _sortBy.value = intent.sort
            is AuthorManageIntent.SetSearchQuery -> _searchQuery.value = intent.query
        }
    }

    private fun filterAuthors(
        authors: ImmutableList<AuthorItemUi>,
        searchQuery: String,
    ): ImmutableList<AuthorItemUi> {
        val query = searchQuery.trim()
        if (query.isBlank()) return authors
        return authors.filter {
            it.name.contains(query, ignoreCase = true) || it.bio.contains(query, ignoreCase = true)
        }.toImmutableList()
    }

    private fun buildAuthors(
        memories: List<ReadingMemory>,
        profiles: Map<String, AuthorProfile>,
        sortBy: AuthorSort,
    ): ImmutableList<AuthorItemUi> {
        val byAuthor = memories.groupBy { it.bookAuthor.trim() }
            .filterKeys { it.isNotBlank() }
        val list = byAuthor.map { (name, mems) ->
            AuthorItemUi(
                name = name,
                bookCount = mems.size,
                readBookCount = mems.count { isAuthorBookFinished(it) },
                avgRating = authorAvgRating(mems),
                bio = profiles[name]?.bio ?: "",
                indexLabel = authorIndexLabel(name),
            )
        }
        val sorted = when (sortBy) {
            AuthorSort.BookCount -> list.sortedByDescending { it.bookCount }
            AuthorSort.Rating -> list.sortedByDescending { it.avgRating }
            AuthorSort.Name -> list.sortedWith(Comparator { a, b -> a.name.cnCompare(b.name) })
        }
        return sorted.toImmutableList()
    }
}
