package io.legado.app.ui.book.readingmemory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.ReadingMemory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingMemoryScreen(
    memories: List<ReadingMemory>,
    statusFilter: ReadingMemoryStatusFilter,
    sortBy: ReadingMemorySortBy,
    onBack: () -> Unit,
    onIntent: (ReadingMemoryIntent) -> Unit,
) {
    var showSortMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读记忆") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = "排序")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            ReadingMemorySortBy.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            sort.label,
                                            fontWeight = if (sort == sortBy) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    onClick = {
                                        onIntent(ReadingMemoryIntent.Sort(sort))
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            ScrollableTabRow(
                selectedTabIndex = ReadingMemoryStatusFilter.entries.indexOf(statusFilter).coerceAtLeast(0),
                edgePadding = 16.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                ReadingMemoryStatusFilter.entries.forEach { filter ->
                    Tab(
                        selected = statusFilter == filter,
                        onClick = { onIntent(ReadingMemoryIntent.Filter(filter)) },
                        text = {
                            Text(
                                filter.label,
                                fontWeight = if (statusFilter == filter) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            if (memories.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "暂无阅读记忆",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(memories, key = { it.bookUrl }) { memory ->
                        ReadingMemoryCard(
                            memory = memory,
                            onClick = {
                                onIntent(ReadingMemoryIntent.ClickBook(memory.bookUrl))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingMemoryCard(memory: ReadingMemory, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = memory.bookName.ifBlank { "未知书名" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (memory.bookAuthor.isNotBlank()) {
                        Text(
                            text = memory.bookAuthor,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (memory.rating > 0) {
                    StarsRow(rating = memory.rating.toInt())
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!memory.intro.isNullOrBlank()) {
                Text(
                    text = memory.intro,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = when {
                        memory.abandoned -> MaterialTheme.colorScheme.errorContainer
                        memory.progress >= 1f -> MaterialTheme.colorScheme.tertiaryContainer
                        memory.progress > 0f -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    tonalElevation = 0.dp
                ) {
                    Text(
                        text = when {
                            memory.abandoned -> "弃文"
                            memory.progress >= 1f -> "已读"
                            memory.progress > 0f -> "在读"
                            else -> "未读"
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (memory.statTotalReadTime > 0) {
                    Text(
                        text = formatReadTime(memory.statTotalReadTime),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StarsRow(rating: Int) {
    Row {
        repeat(5) { index ->
            Text(
                text = if (index < rating) "\u2605" else "\u2606",
                color = if (index < rating) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatReadTime(millis: Long): String {
    val hours = millis / 3600000
    val minutes = (millis % 3600000) / 60000
    return when {
        hours > 0 -> "$hours 小时 ${minutes} 分钟"
        minutes > 0 -> "$minutes 分钟"
        else -> "不到 1 分钟"
    }
}
