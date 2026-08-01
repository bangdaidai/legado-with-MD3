package io.legado.app.ui.book.readingmemory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.ui.book.readingmemory.detail.ReadingMemoryRatingBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.AppPullToRefresh
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.ToggleChip
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.card.TagChip
import io.legado.app.ui.widget.components.card.TagChipSize
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.image.cover.CoilBookCover
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.tabRow.AppTabRow
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReadingMemoryScreen(
    uiState: ReadingMemoryUiState,
    onIntent: (ReadingMemoryIntent) -> Unit,
    onBack: () -> Unit,
) {
    AppLog.put("[阅读记忆] MemoryScreen 渲染 items=${uiState.items.size} sortBy=${uiState.sortBy.label} groupBy=${uiState.groupBy.label}")
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var longPressBookUrl by remember { mutableStateOf<String?>(null) }

    val statusTabs = remember { ReadingMemoryStatusFilter.entries.map { it.label } }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                GlassMediumFlexibleTopAppBar(
                    title = stringResource(R.string.reading_memory),
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        TopBarNavigationButton(onClick = onBack)
                    },
                    actions = {
                        TopBarActionButton(
                            onClick = {
                                showSearch = !showSearch
                                if (!showSearch) {
                                    onIntent(ReadingMemoryIntent.Search(""))
                                }
                            },
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(R.string.search),
                        )
                        TopBarActionButton(
                            onClick = { showMenu = true },
                            imageVector = AppIcons.MoreVert,
                            contentDescription = stringResource(R.string.more_actions),
                        )
                        RoundDropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) { dismiss ->
                            ReadingMemorySortBy.entries.forEach { s ->
                                RoundDropdownMenuItem(
                                    text = s.label,
                                    isSelected = s == uiState.sortBy,
                                    onClick = {
                                        onIntent(ReadingMemoryIntent.SetSortBy(s))
                                        dismiss()
                                    },
                                )
                            }
                            HorizontalDivider()
                            ReadingMemoryGroupBy.entries.forEach { g ->
                                RoundDropdownMenuItem(
                                    text = g.label,
                                    isSelected = g == uiState.groupBy,
                                    onClick = {
                                        onIntent(ReadingMemoryIntent.SetGroupBy(g))
                                        dismiss()
                                    },
                                )
                            }
                            HorizontalDivider()
                            RoundDropdownMenuItem(
                                text = stringResource(R.string.reading_memory_filter_rating),
                                onClick = {
                                    dismiss()
                                    showFilterSheet = true
                                },
                            )
                            HorizontalDivider()
                            RoundDropdownMenuItem(
                                text = stringResource(R.string.reading_memory_show_card),
                                isSelected = uiState.showCard,
                                onClick = {
                                    onIntent(ReadingMemoryIntent.ToggleShowCard(!uiState.showCard))
                                    dismiss()
                                },
                            )
                            RoundDropdownMenuItem(
                                text = stringResource(R.string.reading_memory_show_intro),
                                isSelected = uiState.showIntro,
                                onClick = {
                                    onIntent(ReadingMemoryIntent.ToggleShowIntro(!uiState.showIntro))
                                    dismiss()
                                },
                            )
                            RoundDropdownMenuItem(
                                text = stringResource(R.string.reading_memory_show_review),
                                isSelected = uiState.showReview,
                                onClick = {
                                    onIntent(ReadingMemoryIntent.ToggleShowReview(!uiState.showReview))
                                    dismiss()
                                },
                            )
                            HorizontalDivider()
                            RoundDropdownMenuItem(
                                text = stringResource(R.string.reading_memory_clear_all),
                                color = LegadoTheme.colorScheme.error,
                                onClick = {
                                    dismiss()
                                    showClearDialog = true
                                },
                            )
                        }
                    },
                )

                AnimatedVisibility(
                    modifier = Modifier.adaptiveHorizontalPadding(),
                    visible = showSearch,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    SearchBar(
                        query = uiState.searchQuery,
                        onQueryChange = { onIntent(ReadingMemoryIntent.Search(it)) },
                        placeholder = stringResource(R.string.reading_memory_search_hint),
                        scrollState = listState,
                        scope = scope,
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AppTabRow(
                tabTitles = statusTabs,
                selectedTabIndex = uiState.statusFilter.ordinal,
                onTabSelected = { index ->
                    onIntent(
                        ReadingMemoryIntent.FilterStatus(ReadingMemoryStatusFilter.entries[index])
                    )
                },
            )

            if (uiState.items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyMessage(stringResource(R.string.reading_memory_empty))
                }
            } else {
                AppPullToRefresh(
                    isRefreshing = uiState.loading,
                    onRefresh = { onIntent(ReadingMemoryIntent.Refresh) },
                    scrollBehavior = scrollBehavior,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(uiState.items) { item ->
                            when (item) {
                                is ReadingMemoryListItem.GroupHeader -> {
                                    GroupHeaderRow(
                                        header = item,
                                        onToggle = {
                                            onIntent(ReadingMemoryIntent.ToggleGroupCollapse(item.key))
                                        },
                                    )
                                }

                                is ReadingMemoryListItem.BookItem -> {
                                    val collapsed = uiState.groupBy != ReadingMemoryGroupBy.None &&
                                        uiState.items.filterIsInstance<ReadingMemoryListItem.GroupHeader>()
                                            .any { it.key == groupKeyFor(uiState, item.memory) && it.collapsed }
                                    if (!collapsed) {
                                        MemoryBookCard(
                                            memory = item.memory,
                                            tags = item.tags,
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
            }
        }
    }

    // 筛选 Sheet
    if (showFilterSheet) {
        AppModalBottomSheet(
            show = showFilterSheet,
            onDismissRequest = { showFilterSheet = false },
            title = stringResource(R.string.reading_memory_filter_rating),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AppText(
                    stringResource(R.string.reading_memory_filter_rating),
                    style = LegadoTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReadingMemoryRatingFilter.entries.forEach { r ->
                        ToggleChip(
                            label = r.label,
                            selected = uiState.ratingFilter == r,
                            onToggle = { onIntent(ReadingMemoryIntent.SetRatingFilter(r)) },
                        )
                    }
                }
                HorizontalDivider()
                AppText(
                    stringResource(R.string.reading_memory_filter_type),
                    style = LegadoTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReadingMemoryReadTypeFilter.entries.forEach { t ->
                        ToggleChip(
                            label = t.label,
                            selected = uiState.readTypeFilter == t,
                            onToggle = { onIntent(ReadingMemoryIntent.SetReadTypeFilter(t)) },
                        )
                    }
                }
                HorizontalDivider()
                ToggleChip(
                    label = stringResource(R.string.reading_memory_only_with_review),
                    selected = uiState.onlyWithReview,
                    onToggle = { onIntent(ReadingMemoryIntent.ToggleOnlyWithReview(!uiState.onlyWithReview)) },
                )
            }
        }
    }

    // 长按菜单 Sheet
    longPressBookUrl?.let { bookUrl ->
        val mem = (uiState.items.filterIsInstance<ReadingMemoryListItem.BookItem>()
            .firstOrNull { it.memory.bookUrl == bookUrl })?.memory
        AppModalBottomSheet(
            show = longPressBookUrl != null,
            onDismissRequest = { longPressBookUrl = null },
            title = mem?.bookName,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (mem?.abandoned == true) {
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.reading_memory_unmark_abandoned),
                        onClick = {
                            onIntent(ReadingMemoryIntent.RemoveAbandoned(bookUrl))
                            longPressBookUrl = null
                        },
                    )
                } else {
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.reading_memory_mark_abandoned),
                        onClick = {
                            onIntent(ReadingMemoryIntent.SetAbandoned(bookUrl))
                            longPressBookUrl = null
                        },
                    )
                }
                RoundDropdownMenuItem(
                    text = stringResource(R.string.reading_memory_delete),
                    color = LegadoTheme.colorScheme.error,
                    onClick = {
                        onIntent(ReadingMemoryIntent.DeleteMemory(bookUrl))
                        longPressBookUrl = null
                    },
                )
            }
        }
    }

    // 清空确认
    if (showClearDialog) {
        AppAlertDialog(
            show = true,
            onDismissRequest = { showClearDialog = false },
            title = stringResource(R.string.reading_memory_clear_all),
            text = stringResource(R.string.reading_memory_clear_all_confirm),
            confirmText = stringResource(R.string.ok),
            onConfirm = {
                onIntent(ReadingMemoryIntent.ClearAll)
                showClearDialog = false
            },
            dismissText = stringResource(R.string.cancel),
            onDismiss = { showClearDialog = false },
        )
    }
}

private fun groupKeyFor(uiState: ReadingMemoryUiState, memory: ReadingMemory): String {
    return when (uiState.groupBy) {
        ReadingMemoryGroupBy.Year -> yearOf(memory)
        ReadingMemoryGroupBy.Rating -> (memory.rating + 0.5f).toInt().coerceIn(0, 5).toString()
        ReadingMemoryGroupBy.Status -> when {
            memory.abandoned -> "abandoned"
            memory.progress >= 1f -> "finished"
            memory.progress > 0f -> "reading"
            else -> "toread"
        }
        ReadingMemoryGroupBy.None -> ""
    }
}

private fun yearOf(memory: ReadingMemory): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = if (memory.firstReadTime > 0) memory.firstReadTime else memory.createTime
    return cal.get(java.util.Calendar.YEAR).toString()
}

private fun formatReadDate(time: Long): String {
    if (time <= 0) return ""
    return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        .format(java.util.Date(time))
}

@Composable
private fun GroupHeaderRow(
    header: ReadingMemoryListItem.GroupHeader,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            text = header.display,
            style = LegadoTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        AppText(
            text = "${header.count}",
            style = LegadoTheme.typography.labelMedium,
            color = LegadoTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = if (header.collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            contentDescription = null,
            tint = LegadoTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MemoryBookCard(
    memory: ReadingMemory,
    tags: List<String> = emptyList(),
    uiState: ReadingMemoryUiState,
    onIntent: (ReadingMemoryIntent) -> Unit,
    onLongPress: (String) -> Unit,
) {
    val rowContent: @Composable RowScope.() -> Unit = {
        CoilBookCover(
            name = memory.bookName.takeIf { it.isNotBlank() },
            author = memory.bookAuthor.takeIf { it.isNotBlank() },
            path = memory.coverUrl?.takeIf { it.isNotBlank() },
            radius = 8.dp,
            modifier = Modifier
                .size(64.dp)
                .clip(MaterialTheme.shapes.medium),
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppText(
                text = memory.bookName,
                style = LegadoTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                text = memory.bookAuthor,
                style = LegadoTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReadingMemoryRatingBar(
                    rating = memory.rating.toFloat(),
                    onRatingChanged = {},
                    enabled = false,
                )
                if (memory.rating > 0) {
                    AppText(
                        text = memory.rating.toString(),
                        style = LegadoTheme.typography.labelSmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val statusText = when {
                memory.abandoned -> "弃文"
                memory.progress >= 1f -> "已读"
                memory.progress > 0f -> "在读"
                else -> "待看"
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = when {
                    memory.abandoned -> LegadoTheme.colorScheme.errorContainer
                    memory.progress >= 1f -> LegadoTheme.colorScheme.tertiaryContainer
                    memory.progress > 0f -> LegadoTheme.colorScheme.primaryContainer
                    else -> LegadoTheme.colorScheme.surfaceVariant
                },
            ) {
                AppText(
                    text = statusText,
                    style = LegadoTheme.typography.labelSmall,
                    color = when {
                        memory.abandoned -> LegadoTheme.colorScheme.onErrorContainer
                        memory.progress >= 1f -> LegadoTheme.colorScheme.onTertiaryContainer
                        memory.progress > 0f -> LegadoTheme.colorScheme.onPrimaryContainer
                        else -> LegadoTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (uiState.showIntro && memory.intro?.isNotBlank() == true) {
                AppText(
                    text = memory.intro ?: "",
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (uiState.showReview && memory.review?.isNotBlank() == true) {
                AppText(
                    text = memory.review ?: "",
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    tags.forEach { tag ->
                        TagChip(
                            tag = tag,
                            size = TagChipSize.Small,
                        )
                    }
                }
            }
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val progressText = if (memory.totalChapterNum > 0) {
                "第 ${memory.durChapterIndex + 1} 章 / 共 ${memory.totalChapterNum} 章"
            } else {
                "第 ${memory.durChapterIndex + 1} 章"
            }
            if (progressText.isNotBlank()) {
                AppText(
                    text = progressText,
                    style = LegadoTheme.typography.labelSmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppText(
                text = formatReadDate(memory.lastReadTime),
                style = LegadoTheme.typography.labelSmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (uiState.showCard) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onIntent(ReadingMemoryIntent.ClickBook(memory.bookUrl)) },
                        onLongClick = { onLongPress(memory.bookUrl) },
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                rowContent()
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { onIntent(ReadingMemoryIntent.ClickBook(memory.bookUrl)) },
                    onLongClick = { onLongPress(memory.bookUrl) },
                )
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            rowContent()
        }
    }
}
