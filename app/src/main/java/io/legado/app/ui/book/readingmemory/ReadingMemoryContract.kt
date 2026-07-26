package io.legado.app.ui.book.readingmemory

/** 阅读记忆列表页状态 */
data class ReadingMemoryUiState(
    val memories: List<ReadingMemoryItem> = emptyList(),
    val filteredMemories: List<ReadingMemoryItem> = emptyList(),
    val statusFilter: ReadingMemoryStatusFilter = ReadingMemoryStatusFilter.All,
    val searchQuery: String = "",
    val sortBy: ReadingMemorySortBy = ReadingMemorySortBy.Recent,
    val loading: Boolean = true,
)

/** 列表项展示数据 */
data class ReadingMemoryItem(
    val bookUrl: String,
    val bookName: String,
    val author: String,
    val coverUrl: String?,
    val intro: String,
    val rating: Int,
    val status: Int,       // 0-未读 1-在读 2-已读 3-弃文
    val abandoned: Boolean,
    val totalReadTime: Long,
    val readDuration: Long,
)

enum class ReadingMemoryStatusFilter(val label: String) {
    All("全部"),
    ToRead("未读"),
    Reading("在读"),
    Finished("已读"),
    Abandoned("弃文"),
}

enum class ReadingMemorySortBy(val label: String) {
    Recent("最近更新"),
    Rating("评分最高"),
    ReadDuration("阅读时长"),
    Name("书名"),
}

sealed interface ReadingMemoryIntent {
    data object Load : ReadingMemoryIntent
    data class Filter(val filter: ReadingMemoryStatusFilter) : ReadingMemoryIntent
    data class Search(val query: String) : ReadingMemoryIntent
    data class Sort(val sortBy: ReadingMemorySortBy) : ReadingMemoryIntent
    data class ClickBook(val bookUrl: String) : ReadingMemoryIntent
    data object Refresh : ReadingMemoryIntent
}

sealed interface ReadingMemoryEffect {
    data class NavigateToDetail(val bookUrl: String) : ReadingMemoryEffect
    data object Back : ReadingMemoryEffect
}
