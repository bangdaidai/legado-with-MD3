package io.legado.app.ui.main.my.authorManage

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import io.legado.app.R
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.domain.model.settings.BookshelfSettings
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/** 作者列表排序方式，顶栏按钮按此顺序循环切换。 */
enum class AuthorSort {
    BookCount,
    Rating,
    Name;

    fun next(): AuthorSort = AuthorSort.entries[(ordinal + 1) % AuthorSort.entries.size]
}

/** 作者列表中的单个作者卡片数据。 */
@Stable
data class AuthorItemUi(
    val name: String,
    val bookCount: Int,
    val readBookCount: Int,
    /** 该作者已打分书籍的平均分（无评分时为 0）。 */
    val avgRating: Float,
    /** 用户自定义简介（来自作者详情页，未设置时为空）。 */
    val bio: String,
    /** 按名称排序时的索引分组标签。 */
    val indexLabel: String,
)

/**
 * 关联书籍的阅读状态，口径与书籍卡片左上角的状态标签一致。
 * 枚举顺序即筛选标签的展示顺序。
 */
enum class AuthorBookStatus(@StringRes val labelResId: Int) {
    ToRead(R.string.author_tab_to_read),
    Reading(R.string.author_tab_reading),
    Finished(R.string.author_tab_finished),
    Abandoned(R.string.author_tab_abandoned),
}

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
    /** 关联书籍总数，不随筛选变化。 */
    val bookCount: Int,
    /** 各阅读状态的书籍数，用于筛选标签；计数为 0 的状态不会出现在这里。 */
    val statusCounts: ImmutableMap<AuthorBookStatus, Int>,
    /** 已应用筛选后的书籍列表。 */
    val books: ImmutableList<AuthorBookItem>,
)

@Stable
data class AuthorManageUiState(
    val loading: Boolean = false,
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
    /** 编辑弹窗中的草稿文本。放在状态里而非弹窗内部，AI 生成结果才能回灌。 */
    val bioDraft: String = "",
    val generatingBio: Boolean = false,
    /** 关联书籍的状态筛选，null 表示不筛选。 */
    val bookFilter: AuthorBookStatus? = null,
    /** 以下书籍卡片相关配置与阅读记忆列表保持一致 */
    val bookshelfSettings: BookshelfSettings = BookshelfSettings(),
    val tagColorMap: ImmutableMap<String, Long> = persistentMapOf(),
    val coverWidth: Int = 84,
)

sealed interface AuthorDetailIntent {
    data object ToggleEditBio : AuthorDetailIntent
    data class UpdateBioDraft(val bio: String) : AuthorDetailIntent
    data class SaveBio(val bio: String) : AuthorDetailIntent
    data object GenerateBio : AuthorDetailIntent
    data object DismissEditBio : AuthorDetailIntent

    /** 再次点击已选中的状态会取消筛选。 */
    data class ToggleBookFilter(val status: AuthorBookStatus) : AuthorDetailIntent
}

sealed interface AuthorDetailEffect {
    data class ShowToast(@StringRes val messageResId: Int) : AuthorDetailEffect

    /** AI 生成失败时展示原始错误，便于排查是没配模型还是请求失败。 */
    data class ShowError(val message: String) : AuthorDetailEffect
}
