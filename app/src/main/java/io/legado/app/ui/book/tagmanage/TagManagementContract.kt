package io.legado.app.ui.book.tagmanage

import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.entities.ExcludedTag
import io.legado.app.data.entities.TagMapping
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class TagManagementUiState(
    val version: Long = 0L, // 每次加载数据递增，绕过 BookTag.equals 只比 id 导致的重组丢失
    val searchQuery: String = "",
    // 主页面只展示标签（与 readdai 一致，分组/映射走弹窗，排除走独立页面）
    val tags: ImmutableList<BookTag> = persistentListOf(),
    val groups: ImmutableList<BookTagGroup> = persistentListOf(),
    val mappings: ImmutableList<TagMapping> = persistentListOf(),
    val tagCounts: Map<Long, Int> = emptyMap(),
    val groupTagCounts: Map<Long, Int> = emptyMap(),
)

sealed interface TagManagementIntent {
    data class Search(val q: String) : TagManagementIntent
    data object Refresh : TagManagementIntent

    data class OpenTagDetail(val tagId: Long) : TagManagementIntent

    data class SaveTag(
        val id: Long,
        val name: String,
        val groupId: Long,
        val color: Long,
    ) : TagManagementIntent

    data class DeleteTag(val tag: BookTag) : TagManagementIntent

    data class SaveGroup(val id: Long, val name: String) : TagManagementIntent
    data class DeleteGroup(val group: BookTagGroup) : TagManagementIntent

    data class DeleteMapping(val mapping: TagMapping) : TagManagementIntent

    data class ExcludeTag(val name: String) : TagManagementIntent
}

sealed interface TagManagementEffect {
    data class NavigateToTagDetail(val tagId: Long) : TagManagementEffect
    data class ShowMessage(val message: String) : TagManagementEffect
}
