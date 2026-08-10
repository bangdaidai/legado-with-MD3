package io.legado.app.ui.book.bookmark

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.repository.BookmarkRepository
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.help.book.ShareCardDataBuilder
import io.legado.app.help.book.ShareCardGenerator
import io.legado.app.utils.FileDoc
import io.legado.app.utils.GSON
import io.legado.app.utils.createFileIfNotExist
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BookmarkGroupHeader(
    val bookName: String,
    val bookAuthor: String
) {
    override fun toString(): String = "$bookName|$bookAuthor"
}

@Stable
data class BookmarkItemUi(
    val id: Long,
    val content: String,
    val chapterName: String,
    val bookText: String,
    val bookName: String,
    val bookAuthor: String,
    val rawBookmark: Bookmark
)

@Stable
data class BookmarkUiState(
    val isLoading: Boolean = false,
    val bookmarks: ImmutableMap<BookmarkGroupHeader, ImmutableList<BookmarkItemUi>> = persistentMapOf(),
    val error: Throwable? = null,
    val searchQuery: String = "",
    val collapsedGroups: ImmutableSet<String> = persistentSetOf(),
    val shareCardBitmap: android.graphics.Bitmap? = null,
    val shareCardLoading: Boolean = false,
    val showShareCard: Boolean = false,
    val shareCardData: io.legado.app.data.entities.ShareCardData? = null,
)

sealed interface AllBookmarkIntent {
    data class SetSearchQuery(val query: String) : AllBookmarkIntent
    data class ToggleGroupCollapse(val group: BookmarkGroupHeader) : AllBookmarkIntent
    data class ToggleAllCollapse(val groups: Set<BookmarkGroupHeader>) : AllBookmarkIntent
    data class UpdateBookmark(val bookmark: Bookmark) : AllBookmarkIntent
    data class DeleteBookmark(val bookmark: Bookmark) : AllBookmarkIntent
    data class Export(val treeUri: Uri, val isMarkdown: Boolean) : AllBookmarkIntent
    data class GenerateShareCard(val bookmark: Bookmark) : AllBookmarkIntent
    data object DismissShareCard : AllBookmarkIntent
    data object ClearAll : AllBookmarkIntent
}

sealed interface AllBookmarkEffect {
    data class ShowMessage(val message: String) : AllBookmarkEffect
}


class AllBookmarkViewModel(
    application: Application,
    private val bookmarkRepository: BookmarkRepository,
    private val readingMemoryRepository: ReadingMemoryRepository,
) : AndroidViewModel(application) {

    private val _searchQuery = MutableStateFlow("")
    private val _collapsedGroups = MutableStateFlow<Set<String>>(emptySet())
    private val _effects = MutableSharedFlow<AllBookmarkEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    // 分享卡片（分享卡片）生成状态：由 GenerateShareCard / DismissShareCard 驱动
    private val _shareCardBitmap = MutableStateFlow<android.graphics.Bitmap?>(null)
    private val _shareCardLoading = MutableStateFlow(false)
    private val _showShareCard = MutableStateFlow(false)
    private val _shareCardData = MutableStateFlow<io.legado.app.data.entities.ShareCardData?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val baseUiState: StateFlow<BookmarkUiState> = combine(
        _searchQuery,
        _collapsedGroups,
        bookmarkRepository.flowAll()
    ) { query, collapsed, allBookmarks ->

        val queryFiltered = if (query.isBlank()) {
            allBookmarks
        } else {
            allBookmarks.filter {
                it.bookName.contains(query, ignoreCase = true) ||
                        it.content.contains(query, ignoreCase = true) ||
                        it.bookAuthor.contains(query, ignoreCase = true)
            }
        }

        val grouped = queryFiltered.asSequence()
            .map { bookmark ->
                BookmarkItemUi(
                    id = bookmark.time,
                    content = bookmark.content,
                    chapterName = bookmark.chapterName,
                    bookText = bookmark.bookText,
                    bookName = bookmark.bookName,
                    bookAuthor = bookmark.bookAuthor,
                    rawBookmark = bookmark
                )
            }
            .groupBy { item ->
                BookmarkGroupHeader(item.bookName, item.bookAuthor)
            }
            .mapValues { (_, items) -> items.toImmutableList() }
            .toImmutableMap()

        BookmarkUiState(
            isLoading = false,
            bookmarks = grouped,
            searchQuery = query,
            collapsedGroups = collapsed.toImmutableSet()
        )
    }.catch { e ->
        emit(BookmarkUiState(isLoading = false, error = e))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BookmarkUiState(isLoading = true)
    )

    /** 在基础状态之上叠加分享卡片预览状态，避免生成时重跑书签查询 */
    val uiState: StateFlow<BookmarkUiState> = combine(
        baseUiState,
        _shareCardBitmap,
        _shareCardLoading,
        _showShareCard,
        _shareCardData,
    ) { base, bitmap, loading, show, shareCardData ->
        base.copy(
            shareCardBitmap = bitmap,
            shareCardLoading = loading,
            showShareCard = show,
            shareCardData = shareCardData,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BookmarkUiState(isLoading = true)
    )

    fun onIntent(intent: AllBookmarkIntent) {
        when (intent) {
            is AllBookmarkIntent.SetSearchQuery -> _searchQuery.value = intent.query
            is AllBookmarkIntent.ToggleGroupCollapse -> toggleGroupCollapse(intent.group)
            is AllBookmarkIntent.ToggleAllCollapse -> toggleAllCollapse(intent.groups)
            is AllBookmarkIntent.UpdateBookmark -> updateBookmark(intent.bookmark)
            is AllBookmarkIntent.DeleteBookmark -> deleteBookmark(intent.bookmark)
            is AllBookmarkIntent.Export -> exportBookmark(intent.treeUri, intent.isMarkdown)
            is AllBookmarkIntent.GenerateShareCard -> generateShareCard(intent.bookmark)
            AllBookmarkIntent.DismissShareCard -> {
                _showShareCard.value = false
                _shareCardBitmap.value = null
                _shareCardData.value = null
            }
            AllBookmarkIntent.ClearAll -> clearAllBookmarks()
        }
    }

    private fun generateShareCard(bookmark: Bookmark) {
        _showShareCard.value = true
        _shareCardLoading.value = true
        _shareCardBitmap.value = null
        _shareCardData.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val memory = readingMemoryRepository.getByNameAuthor(bookmark.bookName, bookmark.bookAuthor)
            val data = ShareCardDataBuilder.buildFromBookmark(bookmark, memory)
            _shareCardData.value = data
            val bitmap = ShareCardGenerator.generate(splitties.init.appCtx, data)
            _shareCardLoading.value = false
            _shareCardBitmap.value = bitmap
        }
    }

    /** 清空全部书签，完成后提示。 */
    private fun clearAllBookmarks() {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkRepository.clearAll()
            _effects.tryEmit(
                AllBookmarkEffect.ShowMessage(
                    getApplication<Application>().getString(R.string.clear_all_bookmarks_done)
                )
            )
        }
    }

    fun toggleGroupCollapse(groupKey: BookmarkGroupHeader) {
        val stringKey = groupKey.toString()
        _collapsedGroups.update { current ->
            if (current.contains(stringKey)) current - stringKey else current + stringKey
        }
    }

    fun toggleAllCollapse(currentKeys: Set<BookmarkGroupHeader>) {
        val stringKeys = currentKeys.map { it.toString() }.toSet()
        _collapsedGroups.update { current ->
            if (current.containsAll(stringKeys) && stringKeys.isNotEmpty()) {
                emptySet()
            } else {
                stringKeys
            }
        }
    }

    fun updateBookmark(bookmark: Bookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkRepository.save(bookmark)
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch(Dispatchers.IO) {
            bookmarkRepository.delete(bookmark)
        }
    }

    fun exportBookmark(treeUri: Uri, isMarkdown: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dateFormat = SimpleDateFormat("yyMMddHHmmss", Locale.getDefault())
                val suffix = if (isMarkdown) "md" else "json"
                val fileName = "bookmark-${dateFormat.format(Date())}.$suffix"

                val dirDoc = FileDoc.fromUri(treeUri, true)
                val fileDoc = dirDoc.createFileIfNotExist(fileName)

                fileDoc.openOutputStream().getOrThrow().use { outputStream ->
                    val allData = bookmarkRepository.getAll()
                    if (isMarkdown) {
                        writeMarkdown(outputStream, allData)
                    } else {
                        GSON.writeToOutputStream(outputStream, allData)
                    }
                }

                _effects.emit(AllBookmarkEffect.ShowMessage("导出成功: $fileName"))
            } catch (e: Exception) {
                e.printStackTrace()
                _effects.emit(AllBookmarkEffect.ShowMessage("导出失败: ${e.message}"))
            }
        }
    }

    private fun writeMarkdown(outputStream: java.io.OutputStream, bookmarks: List<Bookmark>) {
        val sb = StringBuilder()
        var lastHeader = ""

        bookmarks.forEach {
            val currentHeader = "${it.bookName}|${it.bookAuthor}"
            if (currentHeader != lastHeader) {
                lastHeader = currentHeader
                sb.append("\n## ${it.bookName} - ${it.bookAuthor}\n\n")
            }
            sb.append("#### ${it.chapterName}\n")
            sb.append("> **原文：** ${it.bookText}\n\n")
            sb.append("${it.content}\n\n")
            sb.append("---\n")
        }
        outputStream.write(sb.toString().toByteArray())
    }
}
