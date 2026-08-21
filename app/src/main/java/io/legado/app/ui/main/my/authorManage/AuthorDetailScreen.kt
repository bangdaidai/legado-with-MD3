package io.legado.app.ui.main.my.authorManage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.book.readingmemory.MemoryBookCard
import io.legado.app.ui.book.readingmemory.detail.ReadingMemoryRatingBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton

@Composable
fun AuthorDetailScreen(
    uiState: AuthorDetailUiState,
    onIntent: (AuthorDetailIntent) -> Unit,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val detail = uiState.detail ?: return
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = detail.name,
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                actions = {
                    TopBarActionButton(
                        onClick = { onIntent(AuthorDetailIntent.ToggleEditBio) },
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.author_edit_bio),
                    )
                },
            )
        },
    ) { contentPadding ->
        LazyColumn(
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                AuthorDetailHeader(
                    detail = detail,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 12.dp),
                )
            }
            items(detail.books, key = { it.memory.bookUrl }) { item ->
                Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                    MemoryBookCard(
                        memory = item.memory,
                        tags = item.tags,
                        settings = uiState.bookshelfSettings,
                        tagColorMap = uiState.tagColorMap,
                        coverWidth = 56,
                        showIntro = false,
                        showReview = true,
                        showAuthor = false,
                        ratingInTitle = true,
                        singleLineTags = true,
                        onBookClick = onOpenBook,
                        onBookLongPress = {},
                    )
                }
            }
        }
    }

    if (uiState.editingBio && detail != null) {
        BioEditDialog(
            name = detail.name,
            initialBio = detail.bio,
            onDismiss = { onIntent(AuthorDetailIntent.DismissEditBio) },
            onSave = { onIntent(AuthorDetailIntent.SaveBio(it)) },
        )
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (detail.avgRating > 0f) {
                    ReadingMemoryRatingBar(
                        rating = detail.avgRating,
                        onRatingChanged = {},
                        enabled = false,
                        starSize = 16.dp,
                    )
                }
                AppText(
                    text = stringResource(R.string.author_read_count, detail.readBookCount)
                            + " · " + stringResource(R.string.author_book_count, detail.bookCount),
                    style = LegadoTheme.typography.labelMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = if (detail.avgRating > 0f) 4.dp else 0.dp),
                )
            }
            if (detail.bio.isNotBlank()) {
                AppText(
                    text = detail.bio,
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurface,
                )
            }
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
