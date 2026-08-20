package io.legado.app.ui.main.my.authorManage

import androidx.compose.runtime.Stable
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.domain.model.settings.BookshelfSettings
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** 作者列表排序方式。 */
enum class AuthorSort(val label: String) {
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

/** 作者详情数据：头部信息 + 关联书籍列表。 */
@Stable
data class AuthorDetailUi(
    val name: String,
    val bio: String,
    val avgRating: Float,
    val readBookCount: Int,
    val bookCount: Int,
    val books: ImmutableList<AuthorBookItem>,
)

@Stable
data class AuthorManageUiState(
    val authors: ImmutableList<AuthorItemUi> = persistentListOf(),
    val sortBy: AuthorSort = AuthorSort.BookCount,
    val searchQuery: String = "",
)

sealed interface AuthorManageIntent {
    data class SetSort(val sort: AuthorSort) : AuthorManageIntent
    data class SetSearchQuery(val query: String) : AuthorManageIntent
}

@Stable
data class AuthorDetailUiState(
    val detail: AuthorDetailUi? = null,
    val editingBio: Boolean = false,
    val bookshelfSettings: BookshelfSettings = BookshelfSettings(),
    val tagColorMap: Map<String, Long> = emptyMap(),
)

sealed interface AuthorDetailIntent {
    data object ToggleEditBio : AuthorDetailIntent
    data class SaveBio(val bio: String) : AuthorDetailIntent
    data object DismissEditBio : AuthorDetailIntent
}

sealed interface AuthorDetailEffect {
    data class ShowToast(val message: String) : AuthorDetailEffect
}
