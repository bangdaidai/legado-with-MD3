package io.legado.app.ui.book.readingmemory

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import io.legado.app.data.entities.ReadingMemory

enum class ReadingMemoryStatusFilter(val label: String) {
    All("全部"), ToRead("未读"), Reading("在读"), Finished("已读"), Abandoned("弃文")
}

enum class ReadingMemorySortBy(val label: String) {
    Recent("最近更新"), Rating("评分最高"), ReadDuration("阅读时长"), Name("书名")
}

enum class ReadingMemoryRatingFilter(val label: String) {
    All("全部"), R5("5星"), R4("4星"), R3("3星"), R2("2星"), R1("1星"), Unrated("未评分")
}

enum class ReadingMemoryReadTypeFilter(val label: String) {
    All("全部"), Text("阅读"), Audio("听书"), Video("看剧")
}

enum class ReadingMemoryGroupBy(val label: String) {
    None("不分组"), Year("按年份"), Rating("按评分"), Status("按状态")
}

@Immutable
sealed interface ReadingMemoryListItem {
    data class GroupHeader(
        val key: String,
        val display: String,
        val count: Int,
        val collapsed: Boolean,
    ) : ReadingMemoryListItem

    data class BookItem(
        val memory: ReadingMemory,
        val tags: List<String> = emptyList(),
    ) : ReadingMemoryListItem
}

@Stable
data class ReadingMemoryUiState(
    val items: List<ReadingMemoryListItem> = emptyList(),
    val statusFilter: ReadingMemoryStatusFilter = ReadingMemoryStatusFilter.All,
    val ratingFilter: ReadingMemoryRatingFilter = ReadingMemoryRatingFilter.All,
    val readTypeFilter: ReadingMemoryReadTypeFilter = ReadingMemoryReadTypeFilter.All,
    val onlyWithReview: Boolean = false,
    val groupBy: ReadingMemoryGroupBy = ReadingMemoryGroupBy.None,
    val sortBy: ReadingMemorySortBy = ReadingMemorySortBy.Recent,
    val showCard: Boolean = true,
    val showIntro: Boolean = true,
    val showReview: Boolean = false,
    val searchQuery: String = "",
    val loading: Boolean = false,
)

sealed interface ReadingMemoryIntent {
    data object Load : ReadingMemoryIntent
    data object Refresh : ReadingMemoryIntent
    data class FilterStatus(val filter: ReadingMemoryStatusFilter) : ReadingMemoryIntent
    data class SetRatingFilter(val filter: ReadingMemoryRatingFilter) : ReadingMemoryIntent
    data class SetReadTypeFilter(val filter: ReadingMemoryReadTypeFilter) : ReadingMemoryIntent
    data class ToggleOnlyWithReview(val value: Boolean) : ReadingMemoryIntent
    data class SetGroupBy(val groupBy: ReadingMemoryGroupBy) : ReadingMemoryIntent
    data class ToggleGroupCollapse(val key: String) : ReadingMemoryIntent
    data class SetSortBy(val sortBy: ReadingMemorySortBy) : ReadingMemoryIntent
    data class ToggleShowCard(val value: Boolean) : ReadingMemoryIntent
    data class ToggleShowIntro(val value: Boolean) : ReadingMemoryIntent
    data class ToggleShowReview(val value: Boolean) : ReadingMemoryIntent
    data class Search(val query: String) : ReadingMemoryIntent
    data class ClickBook(val bookUrl: String) : ReadingMemoryIntent
    data class SetAbandoned(val bookUrl: String) : ReadingMemoryIntent
    data class RemoveAbandoned(val bookUrl: String) : ReadingMemoryIntent
    data class DeleteMemory(val bookUrl: String) : ReadingMemoryIntent
    data object ClearAll : ReadingMemoryIntent
}

sealed interface ReadingMemoryEffect {
    data class NavigateToDetail(val bookUrl: String) : ReadingMemoryEffect
}
