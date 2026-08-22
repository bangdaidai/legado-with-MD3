package io.legado.app.ui.main.my.authorManage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import io.legado.app.ui.widget.components.EmptyMessage
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
    val detail = uiState.detail
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = detail?.name ?: stringResource(R.string.author_management),
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                actions = {
                    if (detail != null) {
                        TopBarActionButton(
                            onClick = { onIntent(AuthorDetailIntent.ToggleEditBio) },
                            imageVector = Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.author_edit_bio),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        if (detail == null) {
            EmptyMessage(
                message = "",
                isLoading = true,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        } else {
            LazyColumn(
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
            ) {
                item {
                    AuthorDetailHeader(
                        detail = detail,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                if (detail.books.isEmpty()) {
                    item {
                        EmptyMessage(
                            message = stringResource(R.string.author_detail_empty),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    items(detail.books, key = { it.memory.bookUrl }) { item ->
                        MemoryBookCard(
                            memory = item.memory,
                            tags = item.tags,
                            settings = uiState.bookshelfSettings,
                            tagColorMap = uiState.tagColorMap,
                            coverWidth = uiState.coverWidth,
                            showIntro = uiState.showIntro,
                            showReview = uiState.showReview,
                            // 作者页不重复显示作者名；评分随之移到标题行，否则会跟着副标题一起消失
                            showAuthor = false,
                            ratingInTitle = true,
                            onBookClick = onOpenBook,
                        )
                    }
                }
            }
        }
    }

    if (uiState.editingBio && detail != null) {
        BioEditDialog(
            bio = uiState.bioDraft,
            generating = uiState.generatingBio,
            onBioChange = { onIntent(AuthorDetailIntent.UpdateBioDraft(it)) },
            onGenerate = { onIntent(AuthorDetailIntent.GenerateBio) },
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
                if (detail.bioIsAi) {
                    AppText(
                        text = stringResource(R.string.author_bio_ai_generated),
                        style = LegadoTheme.typography.labelSmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BioEditDialog(
    bio: String,
    generating: Boolean,
    onBioChange: (String) -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    AppAlertDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.author_edit_bio),
        confirmText = stringResource(R.string.ok),
        onConfirm = { onSave(bio.trim()) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = bio,
                    onValueChange = onBioChange,
                    placeholder = { AppText(stringResource(R.string.author_bio_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    singleLine = false,
                    enabled = !generating,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onGenerate, enabled = !generating) {
                        AppText(
                            text = stringResource(
                                if (generating) R.string.author_bio_generating
                                else R.string.author_bio_generate
                            )
                        )
                    }
                    if (generating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    }
                }
                AppText(
                    text = stringResource(R.string.author_bio_ai_warning),
                    style = LegadoTheme.typography.labelSmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
