package io.legado.app.ui.book.marking

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.BookMarking
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.domain.gateway.BookMarkingGateway
import io.legado.app.domain.model.TextProcessAnchor
import io.legado.app.help.book.BookplateDataBuilder
import io.legado.app.help.book.BookplateGenerator
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
import splitties.init.appCtx

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
    val editing: BookMarking? = null,
    val bookplateBitmap: android.graphics.Bitmap? = null,
    val bookplateLoading: Boolean = false,
    val showBookplate: Boolean = false,
    val bookplateData: io.legado.app.data.entities.BookplateData? = null,
)

sealed interface AllMarkingIntent {
    data class SetSearchQuery(val query: String) : AllMarkingIntent
    data class ToggleGroupCollapse(val group: MarkingGroupHeader) : AllMarkingIntent
    data class ToggleAllCollapse(val groups: Set<MarkingGroupHeader>) : AllMarkingIntent
    data class DeleteMarking(val id: String) : AllMarkingIntent
    data class OpenEdit(val id: String) : AllMarkingIntent
    data object CloseEdit : AllMarkingIntent
    data class SaveMarkingNote(val id: String, val note: String) : AllMarkingIntent
    data class GenerateBookplate(val marking: BookMarking) : AllMarkingIntent
    data object DismissBookplate : AllMarkingIntent
}

sealed interface AllMarkingEffect {
    data class ShowMessage(val message: String) : AllMarkingEffect
}

class AllMarkingViewModel(
    private val bookMarkingGateway: BookMarkingGateway,
    private val readingMemoryRepository: ReadingMemoryRepository,
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _collapsedGroups = MutableStateFlow<Set<String>>(emptySet())
    private val _effects = MutableSharedFlow<AllMarkingEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    /** 笔记编辑 Sheet 当前编辑的笔记（点列表项进入）。 */
    private val _editing = MutableStateFlow<BookMarking?>(null)

    // 书摘票生成状态：由 GenerateBookplate / DismissBookplate 驱动
    private val _bookplateBitmap = MutableStateFlow<android.graphics.Bitmap?>(null)
    private val _bookplateLoading = MutableStateFlow(false)
    private val _showBookplate = MutableStateFlow(false)
    private val _bookplateData = MutableStateFlow<io.legado.app.data.entities.BookplateData?>(null)

    private val baseUiState: StateFlow<MarkingUiState> = combine(
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

    /** 在基础状态之上叠加编辑/书摘票预览状态，避免生成书摘票时重跑数据库查询 */
    val uiState: StateFlow<MarkingUiState> = combine(
        baseUiState,
        _editing,
        combine(
            _bookplateBitmap,
            _bookplateLoading,
            _showBookplate,
            _bookplateData,
        ) { bitmap, loading, show, data -> BookplateOverlay(bitmap, loading, show, data) },
    ) { base, editing, overlay ->
        base.copy(
            editing = editing,
            bookplateBitmap = overlay.bitmap,
            bookplateLoading = overlay.loading,
            showBookplate = overlay.show,
            bookplateData = overlay.data,
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
            is AllMarkingIntent.OpenEdit -> openEdit(intent.id)
            is AllMarkingIntent.CloseEdit -> _editing.value = null
            is AllMarkingIntent.SaveMarkingNote -> saveMarkingNote(intent.id, intent.note)
            is AllMarkingIntent.GenerateBookplate -> generateBookplate(intent.marking)
            is AllMarkingIntent.DismissBookplate -> {
                _showBookplate.value = false
                _bookplateBitmap.value = null
                _bookplateData.value = null
            }
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

    private fun openEdit(id: String) {
        viewModelScope.launch {
            _editing.value = bookMarkingGateway.getById(id)
        }
    }

    /** 只改备注：样式/锚点沿用已有笔记。flowAll() 是 Room Flow，列表会自动刷新。 */
    private fun saveMarkingNote(id: String, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookMarkingGateway.getById(id)?.let { marking ->
                bookMarkingGateway.upsert(
                    marking.copy(note = note, updatedAt = System.currentTimeMillis())
                )
            }
        }
        _editing.value = null
    }

    /** 从划线笔记生成书摘票并就地展示在预览弹窗中。 */
    private fun generateBookplate(marking: BookMarking) {
        _showBookplate.value = true
        _bookplateLoading.value = true
        _bookplateBitmap.value = null
        _bookplateData.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val memory = readingMemoryRepository.getByNameAuthor(
                marking.bookName,
                marking.bookAuthor,
            )
            val data = BookplateDataBuilder.buildFromMarking(marking, memory)
            _bookplateData.value = data
            val bitmap = BookplateGenerator.generate(appCtx, data)
            _bookplateLoading.value = false
            _bookplateBitmap.value = bitmap
        }
    }

    /** 4 路书摘票状态先合成一组，外层 combine 才不超过 5 路上限。 */
    private data class BookplateOverlay(
        val bitmap: android.graphics.Bitmap?,
        val loading: Boolean,
        val show: Boolean,
        val data: io.legado.app.data.entities.BookplateData?,
    )
}
