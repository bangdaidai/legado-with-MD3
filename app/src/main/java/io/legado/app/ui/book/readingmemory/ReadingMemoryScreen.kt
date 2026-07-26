package io.legado.app.ui.book.readingmemory

import io.legado.app.R
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.ui.widget.components.image.cover.BookCoverImage
import io.legado.app.ui.widget.components.AppLinearProgressIndicator
import io.legado.app.ui.book.readingmemory.ReadingMemoryListItem.BookItem
import io.legado.app.ui.book.readingmemory.ReadingMemoryListItem.GroupHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingMemoryScreen(
    uiState: ReadingMemoryUiState,
    onBack: () -> Unit,
    onIntent: (ReadingMemoryIntent) -> Unit,
) {
    var showSearch by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showGroupSheet by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var longPressBookUrl by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.reading_memory)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索")
                    }
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "筛选")
                    }
                    IconButton(onClick = { showGroupSheet = true }) {
                        Icon(Icons.Filled.AccountTree, contentDescription = "分组")
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = "排序")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false },
                        ) {
                            ReadingMemorySortBy.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            s.label,
                                            fontWeight = if (s == uiState.sortBy) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        onIntent(ReadingMemoryIntent.SetSortBy(s))
                                        showSortMenu = false
                                    },
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = uiState.showCard,
                                            onCheckedChange = { },
                                        )
                                        Text(stringResource(R.string.reading_memory_show_card))
                                    }
                                },
                                onClick = { onIntent(ReadingMemoryIntent.ToggleShowCard(!uiState.showCard)) },
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = uiState.showIntro,
                                            onCheckedChange = { },
                                        )
                                        Text(stringResource(R.string.reading_memory_show_intro))
                                    }
                                },
                                onClick = { onIntent(ReadingMemoryIntent.ToggleShowIntro(!uiState.showIntro)) },
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = uiState.showReview,
                                            onCheckedChange = { },
                                        )
                                        Text(stringResource(R.string.reading_memory_show_review))
                                    }
                                },
                                onClick = { onIntent(ReadingMemoryIntent.ToggleShowReview(!uiState.showReview)) },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.reading_memory_clear_all),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    showMoreMenu = false
                                    showClearDialog = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (showSearch) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { onIntent(ReadingMemoryIntent.Search(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    placeholder = { Text(stringResource(R.string.reading_memory_search_hint)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                onIntent(ReadingMemoryIntent.Search(""))
                            }) { Icon(Icons.Filled.Clear, contentDescription = null) }
                        }
                    },
                )
            }

            ScrollableTabRow(
                selectedTabIndex = ReadingMemoryStatusFilter.entries
                    .indexOf(uiState.statusFilter)
                    .coerceAtLeast(0),
                edgePadding = 16.dp,
            ) {
                ReadingMemoryStatusFilter.entries.forEach { f ->
                    Tab(
                        selected = uiState.statusFilter == f,
                        onClick = { onIntent(ReadingMemoryIntent.FilterStatus(f)) },
                        text = {
                            Text(
                                f.label,
                                fontWeight = if (uiState.statusFilter == f) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }

            if (uiState.items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.reading_memory_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = 12.dp,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = uiState.items,
                        key = { item ->
                            when (item) {
                                is GroupHeader -> "h_${item.key}"
                                is BookItem -> item.memory.bookUrl
                            }
                        },
                        contentType = { it::class },
                    ) { item ->
                        when (item) {
                            is GroupHeader -> GroupHeaderRow(item) {
                                onIntent(ReadingMemoryIntent.ToggleGroupCollapse(item.key))
                            }
                            is BookItem -> MemoryBookCard(
                                memory = item.memory,
                                uiState = uiState,
                                onIntent = onIntent,
                                onLongPress = { longPressBookUrl = it },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(onDismissRequest = { showFilterSheet = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(
                    stringResource(R.string.reading_memory_filter_rating),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                ReadingMemoryRatingFilter.entries.forEach { r ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onIntent(ReadingMemoryIntent.SetRatingFilter(r)) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = uiState.ratingFilter == r,
                            onClick = { onIntent(ReadingMemoryIntent.SetRatingFilter(r)) },
                        )
                        Text(r.label)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    stringResource(R.string.reading_memory_filter_type),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                ReadingMemoryReadTypeFilter.entries.forEach { t ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onIntent(ReadingMemoryIntent.SetReadTypeFilter(t)) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = uiState.readTypeFilter == t,
                            onClick = { onIntent(ReadingMemoryIntent.SetReadTypeFilter(t)) },
                        )
                        Text(t.label)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = uiState.onlyWithReview,
                        onCheckedChange = { onIntent(ReadingMemoryIntent.ToggleOnlyWithReview(it)) },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.reading_memory_only_with_review))
                }
            }
        }
    }

    if (showGroupSheet) {
        ModalBottomSheet(onDismissRequest = { showGroupSheet = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text(
                    stringResource(R.string.reading_memory_group_by),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                ReadingMemoryGroupBy.entries.forEach { g ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onIntent(ReadingMemoryIntent.SetGroupBy(g))
                                showGroupSheet = false
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = uiState.groupBy == g,
                            onClick = {
                                onIntent(ReadingMemoryIntent.SetGroupBy(g))
                                showGroupSheet = false
                            },
                        )
                        Text(g.label)
                    }
                }
            }
        }
    }

    longPressBookUrl?.let { bookUrl ->
        val mem = uiState.items
            .filterIsInstance<BookItem>()
            .firstOrNull { it.memory.bookUrl == bookUrl }
            ?.memory
        ModalBottomSheet(onDismissRequest = { longPressBookUrl = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                mem?.let {
                    Text(
                        it.bookName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.padding(4.dp))
                }
                if (mem?.abandoned == true) {
                    TextButton(
                        onClick = {
                            onIntent(ReadingMemoryIntent.RemoveAbandoned(bookUrl))
                            longPressBookUrl = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.reading_memory_unmark_abandoned)) }
                } else {
                    TextButton(
                        onClick = {
                            onIntent(ReadingMemoryIntent.SetAbandoned(bookUrl))
                            longPressBookUrl = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.reading_memory_mark_abandoned)) }
                }
                TextButton(
                    onClick = {
                        onIntent(ReadingMemoryIntent.DeleteMemory(bookUrl))
                        longPressBookUrl = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.reading_memory_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.reading_memory_clear_all)) },
            text = { Text(stringResource(R.string.reading_memory_clear_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onIntent(ReadingMemoryIntent.ClearAll)
                    showClearDialog = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun GroupHeaderRow(header: GroupHeader, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (header.collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            header.display,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "(${header.count})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MemoryBookCard(
    memory: ReadingMemory,
    uiState: ReadingMemoryUiState,
    onIntent: (ReadingMemoryIntent) -> Unit,
    onLongPress: (String) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (uiState.showCard) 1.dp else 0.dp,
        color = if (uiState.showCard) MaterialTheme.colorScheme.surface else Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onIntent(ReadingMemoryIntent.ClickBook(memory.bookUrl)) },
                    onLongClick = { onLongPress(memory.bookUrl) },
                )
                .padding(12.dp),
        ) {
            BookCoverImage(
                name = memory.bookName,
                author = memory.bookAuthor,
                path = memory.coverUrl,
                modifier = Modifier
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.medium),
                contentScale = ContentScale.Crop,
            )
            Spacer(Modifier.width(12.dp))

            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        memory.bookName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (memory.rating > 0f) {
                        Spacer(Modifier.width(8.dp))
                        StarsRow(memory.rating.toInt())
                    }
                }

                Text(
                    memory.bookAuthor,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (uiState.showIntro && !memory.intro.isNullOrBlank()) {
                    Spacer(Modifier.padding(top = 4.dp))
                    Text(
                        memory.intro!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (uiState.showReview && !memory.review.isNullOrBlank()) {
                    Spacer(Modifier.padding(top = 4.dp))
                    Text(
                        memory.review!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                val protagonists = remember(memory.protagonistsJson) {
                    memory.protagonistsJson
                        ?.split("|")
                        ?.filter { it.isNotBlank() }
                        ?.take(4) ?: emptyList()
                }
                val tags = buildList {
                    memory.kind?.takeIf { it.isNotBlank() }?.let { add(it) }
                    addAll(protagonists)
                }
                if (tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 6.dp),
                    ) {
                        tags.forEach { t ->
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    t,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }

                // 进度条
                Spacer(Modifier.padding(top = 6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppLinearProgressIndicator(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp),
                        progress = memory.progress.coerceIn(0f, 1f),
                    )
                    Text(
                        "${(memory.progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.padding(top = 6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val statusInfo = when {
                        memory.abandoned -> "弃文" to MaterialTheme.colorScheme.errorContainer
                        memory.progress >= 1f -> "已读" to MaterialTheme.colorScheme.tertiaryContainer
                        memory.progress > 0f -> "在读" to MaterialTheme.colorScheme.primaryContainer
                        else -> "未读" to MaterialTheme.colorScheme.surfaceVariant
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = statusInfo.second,
                    ) {
                        Text(
                            statusInfo.first,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    if (memory.wordCount?.isNotBlank() == true) {
                        Text(
                            memory.wordCount!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (memory.statTotalWords > 0) {
                        Text(
                            formatWordCount(memory.statTotalWords) + "字",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (memory.statTotalReadTime > 0) {
                        Text(
                            formatReadTime(memory.statTotalReadTime),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StarsRow(rating: Int) {
    val v = rating.coerceIn(0, 5)
    Text(
        "★".repeat(v) + "☆".repeat(5 - v),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

private fun formatReadTime(ms: Long): String {
    val totalMinutes = ms / 60000
    val days = totalMinutes / (60 * 24)
    val hours = (totalMinutes % (60 * 24)) / 60
    val minutes = totalMinutes % 60
    return buildString {
        if (days > 0L) append("${days}天")
        if (hours > 0L) append("${hours}时")
        if (minutes > 0L || (days == 0L && hours == 0L)) append("${minutes}分")
    }.let { if (it.isEmpty()) "0分" else it }
}

private fun formatWordCount(words: Long): String {
    if (words <= 0) return "0"
    return when {
        words >= 10000 -> "${words / 10000}万"
        words >= 1000 -> "${words / 1000}千"
        else -> "$words"
    }
}
