package io.legado.app.ui.main.my.authorManage

import androidx.compose.runtime.Stable
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.ui.book.readingmemory.ReadingMemoryStatusFilter
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** 作者列表排序方式。 */
enum class AuthorSort(val label: String) {
    Name("名称"),
    BookCount("书籍数"),
    Rating("评分"),
}

/** 作者列表中的单个作者卡片数据。 */
@Stable
data class AuthorItemUi(
    val name: String,
    val bookCount: Int,
    val readBookCount: Int,
    /** 该作者已读书籍评分的平均分（无评分时为 0）。 */
    val avgRating: Float,
    /** 用户自定义简介（来自作者详情页，未设置时为空）。 */
    val bio: String,
)

/** 作者详情中单本书籍条目（复用阅读记忆书籍卡片所需）。 */
@Stable
data class AuthorBookItem(
    val memory: ReadingMemory,
    val tags: ImmutableList<String> = persistentListOf(),
)

/** 作者详情数据：头部信息 + 按阅读状态分组的书籍列表。 */
@Stable
data class AuthorDetailUi(
    val name: String,
    val bio: String,
    val avgRating: Float,
    val readBookCount: Int,
    val bookCount: Int,
    val booksByStatus: Map<ReadingMemoryStatusFilter, ImmutableList<AuthorBookItem>>,
)

@Stable
data class AuthorManageUiState(
    val authors: ImmutableList<AuthorItemUi> = persistentListOf(),
    val sortBy: AuthorSort = AuthorSort.Rating,
    val selectedAuthorName: String? = null,
    val detailStatus: ReadingMemoryStatusFilter = ReadingMemoryStatusFilter.Finished,
    val detail: AuthorDetailUi? = null,
    val editingBio: Boolean = false,
    val bookshelfSettings: BookshelfSettings = BookshelfSettings(),
    val tagColorMap: Map<String, Long> = emptyMap(),
)

sealed interface AuthorManageIntent {
    data class SetSort(val sort: AuthorSort) : AuthorManageIntent
    data class ClickAuthor(val name: String) : AuthorManageIntent
    data object Back : AuthorManageIntent
    data class SetDetailStatus(val status: ReadingMemoryStatusFilter) : AuthorManageIntent
    data class ToggleEditBio(val show: Boolean) : AuthorManageIntent
    data class SaveBio(val name: String, val bio: String) : AuthorManageIntent
}

sealed interface AuthorManageEffect {
    data class ShowToast(val message: String) : AuthorManageEffect
}
