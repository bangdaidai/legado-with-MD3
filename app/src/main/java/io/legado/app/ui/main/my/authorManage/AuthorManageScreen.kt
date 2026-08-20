package io.legado.app.ui.main.my.authorManage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.book.readingmemory.MemoryBookCard
import io.legado.app.ui.book.readingmemory.ReadingMemoryStatusFilter
import io.legado.app.ui.book.readingmemory.detail.ReadingMemoryRatingBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppText
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton

@Composable
fun AuthorManageScreen(
    uiState: AuthorManageUiState,
    onIntent: (AuthorManageIntent) -> Unit,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    if (uiState.selectedAuthorName != null && uiState.detail != null) {
        AuthorDetailScreen(uiState, onIntent, onBack, onOpenBook)
    } else {
        AuthorListScreen(uiState, onIntent, onBack)
    }

    if (uiState.editingBio && uiState.detail != null) {
        BioEditDialog(
            name = uiState.detail!!.name,
            initialBio = uiState.detail!!.bio,
            onDismiss = { onIntent(AuthorManageIntent.ToggleEditBio(false)) },
            onSave = { onIntent(AuthorManageIntent.SaveBio(uiState.detail!!.name, it)) },
        )
    }
}

@Composable
private fun AuthorListScreen(
    uiState: AuthorManageUiState,
    onIntent: (AuthorManageIntent) -> Unit,
    onBack: () -> Unit,
) {
    AppScaffold(
        topBar = {
            Column {
                GlassMediumFlexibleTopAppBar(
                    title = stringResource(R.string.author_management),
                    navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                )
                AuthorSortRow(uiState.sortBy) { onIntent(AuthorManageIntent.SetSort(it)) }
            }
        },
    ) { contentPadding ->
        if (uiState.authors.isEmpty()) {
            EmptyMessage(
                text = stringResource(R.string.author_management_empty),
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
        } else {
            LazyColumn(
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(uiState.authors, key = { it.name }) { author ->
                    AuthorCard(
                        author = author,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        onClick = { onIntent(AuthorManageIntent.ClickAuthor(author.name)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthorSortRow(
    sortBy: AuthorSort,
    onSort: (AuthorSort) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuthorSort.entries.forEach { sort ->
            FilterChip(
                selected = sortBy == sort,
                onClick = { onSort(sort) },
                label = { AppText(sort.label) },
            )
        }
    }
}

@Composable
private fun AuthorCard(
    author: AuthorItemUi,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    NormalCard(onClick = onClick, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    text = author.name,
                    style = LegadoTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (author.avgRating > 0f) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ReadingMemoryRatingBar(
                            rating = author.avgRating,
                            onRatingChanged = {},
                            enabled = false,
                            starSize = 14.dp,
                        )
                        AppText(
                            text = String.format("%.1f", author.avgRating),
                            style = LegadoTheme.typography.labelSmall,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            AppText(
                text = author.bio.ifBlank { stringResource(R.string.author_no_bio) },
                style = LegadoTheme.typography.bodySmall,
                color = if (author.bio.isBlank()) {
                    LegadoTheme.colorScheme.onSurfaceVariant
                } else {
                    LegadoTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                text = stringResource(R.string.author_read_count, author.readBookCount)
                        + " · " + stringResource(R.string.author_book_count, author.bookCount),
                style = LegadoTheme.typography.labelSmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AuthorDetailScreen(
    uiState: AuthorManageUiState,
    onIntent: (AuthorManageIntent) -> Unit,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val detail = uiState.detail ?: return
    AppScaffold(
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = detail.name,
                navigationIcon = { TopBarNavigationButton(onClick = { onIntent(AuthorManageIntent.Back) }) },
                actions = {
                    TopBarActionButton(
                        onClick = { onIntent(AuthorManageIntent.ToggleEditBio(true)) },
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.author_edit_bio),
                    )
                },
            )
        },
    ) { contentPadding ->
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            AuthorDetailHeader(
                detail = detail,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(top = 12.dp),
            )
            AuthorDetailTabs(
                detail = detail,
                selectedStatus = uiState.detailStatus,
                onSelect = { onIntent(AuthorManageIntent.SetDetailStatus(it)) },
            )
            val books = detail.booksByStatus[uiState.detailStatus] ?: emptyList()
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                items(books, key = { it.memory.bookUrl }) { item ->
                    MemoryBookCard(
                        memory = item.memory,
                        tags = item.tags,
                        settings = uiState.bookshelfSettings,
                        tagColorMap = uiState.tagColorMap,
                        coverWidth = 56,
                        showIntro = true,
                        showReview = true,
                        onBookClick = onOpenBook,
                        onBookLongPress = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthorDetailHeader(
    detail: AuthorDetailUi,
    modifier: Modifier = Modifier,
) {
    NormalCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AppText(
                text = detail.name,
                style = LegadoTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReadingMemoryRatingBar(
                    rating = detail.avgRating,
                    onRatingChanged = {},
                    enabled = false,
                    starSize = 16.dp,
                )
                if (detail.avgRating > 0f) {
                    AppText(
                        text = String.format("%.1f", detail.avgRating),
                        style = LegadoTheme.typography.labelMedium,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                AppText(
                    text = "  " + stringResource(R.string.author_read_count, detail.readBookCount)
                            + " · " + stringResource(R.string.author_book_count, detail.bookCount),
                    style = LegadoTheme.typography.labelMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppText(
                text = detail.bio.ifBlank { stringResource(R.string.author_no_bio) },
                style = LegadoTheme.typography.bodySmall,
                color = if (detail.bio.isBlank()) {
                    LegadoTheme.colorScheme.onSurfaceVariant
                } else {
                    LegadoTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun AuthorDetailTabs(
    detail: AuthorDetailUi,
    selectedStatus: ReadingMemoryStatusFilter,
    onSelect: (ReadingMemoryStatusFilter) -> Unit,
) {
    val tabs = listOf(
        ReadingMemoryStatusFilter.Finished to stringResource(R.string.author_tab_finished),
        ReadingMemoryStatusFilter.Reading to stringResource(R.string.author_tab_reading),
        ReadingMemoryStatusFilter.ToRead to stringResource(R.string.author_tab_to_read),
        ReadingMemoryStatusFilter.Abandoned to stringResource(R.string.author_tab_abandoned),
    )
    val selectedIndex = tabs.indexOfFirst { it.first == selectedStatus }.coerceAtLeast(0)
    ScrollableTabRow(selectedTabIndex = selectedIndex) {
        tabs.forEachIndexed { index, (status, label) ->
            val count = detail.booksByStatus[status]?.size ?: 0
            Tab(
                selected = index == selectedIndex,
                onClick = { onSelect(status) },
                text = { AppText("$label $count") },
            )
        }
    }
}

@Composable
private fun BioEditDialog(
    name: String,
    initialBio: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(name) { mutableStateOf(initialBio) }
    AppAlertDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.author_edit_bio),
        confirmText = stringResource(R.string.ok),
        onConfirm = { onSave(text.trim()) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = onDismiss,
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { AppText(stringResource(R.string.author_bio_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                singleLine = false,
            )
        },
    )
}
