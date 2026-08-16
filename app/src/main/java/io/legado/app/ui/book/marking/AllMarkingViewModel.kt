package io.legado.app.ui.book.marking

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.BookMarking
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.domain.gateway.BookMarkingGateway
import io.legado.app.domain.model.TextProcessAnchor
import io.legado.app.help.book.ShareCardDataBuilder
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.createFileIfNotExist
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.writeToOutputStream
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val shareCardLoading: Boolean = false,
    val showShareCard: Boolean = false,
    val shareCardData: io.legado.app.data.entities.ShareCardData? = null,
)

sealed interface AllMarkingIntent {
    data class SetSearchQuery(val query: String) : AllMarkingIntent
    data class ToggleGroupCollapse(val group: MarkingGroupHeader) : AllMarkingIntent
    data class ToggleAllCollapse(val groups: Set<MarkingGroupHeader>) : AllMarkingIntent
    data class DeleteMarking(val id: String) : AllMarkingIntent
    data class Export(val treeUri: Uri, val isMarkdown: Boolean) : AllMarkingIntent
    data class OpenEdit(val id: String) : AllMarkingIntent
    data object CloseEdit : AllMarkingIntent
    data class SaveMarkingNote(val id: String, val note: String) : AllMarkingIntent
    data class GenerateShareCard(val marking: BookMarking) : AllMarkingIntent
    data object DismissShareCard : AllMarkingIntent
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

    // 分享卡片生成状态：由 GenerateShareCard / DismissShareCard 驱动
    private val _shareCardLoading = MutableStateFlow(false)
    private val _showShareCard = MutableStateFlow(false)
    private val _shareCardData = MutableStateFlow<io.legado.app.data.entities.ShareCardData?>(null)

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

    /** 在基础状态之上叠加编辑/分享卡片预览状态，避免生成分享卡片时重跑数据库查询 */
    val uiState: StateFlow<MarkingUiState> = combine(
        baseUiState,
        _editing,
        combine(
            _shareCardLoading,
            _showShareCard,
            _shareCardData,
        ) { loading, show, data -> ShareCardOverlay(loading, show, data) },
    ) { base, editing, overlay ->
        base.copy(
            editing = editing,
            shareCardLoading = overlay.loading,
            showShareCard = overlay.show,
            shareCardData = overlay.data,
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
            is AllMarkingIntent.Export -> exportMarking(intent.treeUri, intent.isMarkdown)
            is AllMarkingIntent.OpenEdit -> openEdit(intent.id)
            is AllMarkingIntent.CloseEdit -> _editing.value = null
            is AllMarkingIntent.SaveMarkingNote -> saveMarkingNote(intent.id, intent.note)
            is AllMarkingIntent.GenerateShareCard -> generateShareCard(intent.marking)
            is AllMarkingIntent.DismissShareCard -> {
                _showShareCard.value = false
                _shareCardData.value = null
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

    private fun exportMarking(treeUri: Uri, isMarkdown: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
                val suffix = if (isMarkdown) "md" else "json"
                val fileName = "markings-${dateFormat.format(Date())}.$suffix"

                val dirDoc = FileDoc.fromUri(treeUri, true)
                val fileDoc = dirDoc.createFileIfNotExist(fileName)

                fileDoc.openOutputStream().getOrThrow().use { outputStream ->
                    val allMarkings = bookMarkingGateway.flowAll().first()
                    if (isMarkdown) {
                        writeMarkdown(outputStream, allMarkings)
                    } else {
                        GSON.writeToOutputStream(outputStream, allMarkings)
                    }
                }

                _effects.emit(AllMarkingEffect.ShowMessage("导出成功: $fileName"))
            } catch (e: Exception) {
                e.printStackTrace()
                _effects.emit(AllMarkingEffect.ShowMessage("导出失败: ${e.message}"))
            }
        }
    }

    private fun writeMarkdown(outputStream: java.io.OutputStream, markings: List<BookMarking>) {
        val sb = StringBuilder()
        var lastHeader = ""
        markings.forEach { marking ->
            val currentHeader = "${marking.bookName}|${marking.bookAuthor}"
            if (currentHeader != lastHeader) {
                lastHeader = currentHeader
                sb.append("\n## ${marking.bookName} - ${marking.bookAuthor}\n\n")
            }
            if (marking.chapterName.isNotBlank()) {
                sb.append("#### ${marking.chapterName}\n")
            }
            val selectedText = GSON.fromJsonObject<TextProcessAnchor>(marking.anchorJson)
                .getOrNull()?.selectedText.orEmpty()
            if (selectedText.isNotBlank()) {
                sb.append("> **原文：** $selectedText\n\n")
            }
            if (marking.note.isNotBlank()) {
                sb.append("${marking.note}\n\n")
            }
            sb.append("---\n")
        }
        outputStream.write(sb.toString().toByteArray())
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

    /** 从划线笔记生成分享卡片数据并就地展示在预览弹窗中（图由弹窗里的 WebView 实时渲染）。 */
    private fun generateShareCard(marking: BookMarking) {
        _showShareCard.value = true
        _shareCardLoading.value = true
        _shareCardData.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val memory = readingMemoryRepository.getByNameAuthor(
                    marking.bookName,
                    marking.bookAuthor,
                )
                _shareCardData.value = ShareCardDataBuilder.buildFromMarking(marking, memory)
            } finally {
                // 构建失败也要收掉转圈，否则预览弹窗会一直停在加载态
                _shareCardLoading.value = false
            }
        }
    }

    /** 分享卡片状态先合成一组，外层 combine 才不超过 5 路上限。 */
    private data class ShareCardOverlay(
        val loading: Boolean,
        val show: Boolean,
        val data: io.legado.app.data.entities.ShareCardData?,
    )
}
