package io.legado.app.ui.book.tagmanage

import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.entities.ExcludedTag
import io.legado.app.data.entities.TagMapping
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class TagManagementUiState(
    val searchQuery: String = "",
    val selectedTab: Int = 0, // 0 标签 / 1 分组 / 2 排除 / 3 映射
    val tags: ImmutableList<BookTag> = persistentListOf(),
    val groups: ImmutableList<BookTagGroup> = persistentListOf(),
    val excludedTags: ImmutableList<ExcludedTag> = persistentListOf(),
    val mappings: ImmutableList<TagMapping> = persistentListOf(),
    val tagCounts: Map<Long, Int> = emptyMap(),
    val groupTagCounts: Map<Long, Int> = emptyMap(),
)

sealed interface TagManagementIntent {
    data class Search(val q: String) : TagManagementIntent
    data class SelectTab(val index: Int) : TagManagementIntent
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

    data class SaveExcluded(
        val id: Long,
        val name: String,
        val isRegex: Boolean,
    ) : TagManagementIntent

    data class DeleteExcluded(val excluded: ExcludedTag) : TagManagementIntent

    data class SaveMapping(
        val id: Long,
        val oldTagName: String,
        val newTagId: Long,
    ) : TagManagementIntent

    data class DeleteMapping(val mapping: TagMapping) : TagManagementIntent
}

sealed interface TagManagementEffect {
    data class NavigateToTagDetail(val tagId: Long) : TagManagementEffect
    data class ShowMessage(val message: String) : TagManagementEffect
}
