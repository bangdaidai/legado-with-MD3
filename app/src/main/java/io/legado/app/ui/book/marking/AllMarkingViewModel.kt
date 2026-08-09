package io.legado.app.ui.book.marking

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.BookMarking
import io.legado.app.domain.gateway.BookMarkingGateway
import io.legado.app.domain.model.TextProcessAnchor
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 「所有笔记」页的分组键：与书签页一致，按「书名+作者」跨源聚合。 */
data class MarkingGroupHeader(
    val bookName: String,
    val bookAuthor: String
) {
    override fun toString(): String = "$bookName|$bookAuthor"
}

@Stable
data class MarkingItemUi(
    val id: String,
    val chapterName: String?,
    val note: String?,
    val textSnippet: String?,
    val bookName: String,
    val bookAuthor: String,
    val raw: BookMarking
)

@Stable
data class MarkingUiState(
    val isLoading: Boolean = false,
    val markings: ImmutableMap<MarkingGroupHeader, ImmutableList<MarkingItemUi>> = persistentMapOf(),
    val searchQuery: String = "",
    val collapsedGroups: ImmutableSet<String> = persistentSetOf(),
)

sealed interface AllMarkingIntent {
    data class SetSearchQuery(val query: String) : AllMarkingIntent
    data class ToggleGroupCollapse(val group: MarkingGroupHeader) : AllMarkingIntent
    data class ToggleAllCollapse(val groups: Set<MarkingGroupHeader>) : AllMarkingIntent
    data class DeleteMarking(val id: String) : AllMarkingIntent
}

sealed interface AllMarkingEffect {
    data class ShowMessage(val message: String) : AllMarkingEffect
}

class AllMarkingViewModel(
    private val bookMarkingGateway: BookMarkingGateway,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _collapsedGroups = MutableStateFlow<Set<String>>(emptySet())
    private val _effects = MutableSharedFlow<AllMarkingEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    val uiState: StateFlow<MarkingUiState> = combine(
        _searchQuery,
        _collapsedGroups,
        bookMarkingGateway.flowAll()
    ) { query, collapsed, allMarkings ->
        val grouped = allMarkings.asSequence()
            .map { marking ->
                val snippet = GSON.fromJsonObject<TextProcessAnchor>(marking.anchorJson)
                    .getOrNull()
                    ?.selectedText
                MarkingItemUi(
                    id = marking.id,
                    chapterName = marking.chapterName.ifBlank { null },
                    note = marking.note.ifBlank { null },
                    textSnippet = snippet?.ifBlank { null },
                    bookName = marking.bookName,
                    bookAuthor = marking.bookAuthor,
                    raw = marking
                )
            }
            .filter { item ->
                query.isBlank() ||
                        item.bookName.contains(query, ignoreCase = true) ||
                        item.bookAuthor.contains(query, ignoreCase = true) ||
                        item.note?.contains(query, ignoreCase = true) == true ||
                        item.textSnippet?.contains(query, ignoreCase = true) == true
            }
            .groupBy { item -> MarkingGroupHeader(item.bookName, item.bookAuthor) }
            .mapValues { (_, items) -> items.toImmutableList() }
            .toImmutableMap()

        MarkingUiState(
            isLoading = false,
            markings = grouped,
            searchQuery = query,
            collapsedGroups = collapsed.toImmutableSet()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MarkingUiState(isLoading = true)
    )

    fun onIntent(intent: AllMarkingIntent) {
        when (intent) {
            is AllMarkingIntent.SetSearchQuery -> _searchQuery.value = intent.query
            is AllMarkingIntent.ToggleGroupCollapse -> toggleGroupCollapse(intent.group)
            is AllMarkingIntent.ToggleAllCollapse -> toggleAllCollapse(intent.groups)
            is AllMarkingIntent.DeleteMarking -> deleteMarking(intent.id)
        }
    }

    private fun toggleGroupCollapse(groupKey: MarkingGroupHeader) {
        val stringKey = groupKey.toString()
        _collapsedGroups.update { current ->
            if (current.contains(stringKey)) current - stringKey else current + stringKey
        }
    }

    private fun toggleAllCollapse(currentKeys: Set<MarkingGroupHeader>) {
        val stringKeys = currentKeys.map { it.toString() }.toSet()
        _collapsedGroups.update { current ->
            if (current.containsAll(stringKeys) && stringKeys.isNotEmpty()) {
                emptySet()
            } else {
                stringKeys
            }
        }
    }

    private fun deleteMarking(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookMarkingGateway.delete(id)
        }
    }
}
