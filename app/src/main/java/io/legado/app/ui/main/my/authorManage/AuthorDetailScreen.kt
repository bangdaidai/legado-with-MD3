package io.legado.app.ui.main.my.authorManage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.book.readingmemory.MemoryBookCard
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.button.ToggleChip
import io.legado.app.ui.widget.components.button.series.SmallTonalButton
import io.legado.app.ui.widget.components.progressIndicator.AppCircularProgressIndicator
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
                // 评分和简介都没有时整张卡是空的，直接不渲染
                if (detail.avgRating > 0f || detail.bio.isNotBlank()) {
                    item {
                        AuthorDetailHeader(
                            detail = detail,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp),
                        )
                    }
                }
                item {
                    RelatedBooksHeader(
                        detail = detail,
                        selected = uiState.bookFilter,
                        onToggleFilter = { onIntent(AuthorDetailIntent.ToggleBookFilter(it)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 4.dp),
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
                            // 作者页固定展示简介和书评，不跟阅读记忆的开关
                            showIntro = true,
                            showReview = true,
                            // 简介只占一行、跟在标签下面；书评放封面下方独立区块，保留票据样式
                            forceInlineIntro = true,
                            inlineIntroMaxLines = 1,
                            forceReviewBelowContent = true,
                            // 作者页不重复显示作者名；评分随之移到标题行，否则会跟着副标题一起消失
                            showAuthor = false,
                            ratingInTitle = true,
                            // 标签只占一行，横向滚动，跟书架列表一致
                            singleLineTags = true,
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

/**
 * 「关联书籍（总数）」标题 + 各状态筛选标签。总数不随筛选变化，
 * 标签点击切换筛选，再点一次取消。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelatedBooksHeader(
    detail: AuthorDetailUi,
    selected: AuthorBookStatus?,
    onToggleFilter: (AuthorBookStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppText(
            text = stringResource(R.string.author_related_books, detail.bookCount),
            style = LegadoTheme.typography.titleMedium,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(end = 2.dp),
        )
        AuthorBookStatus.entries.forEach { status ->
            val count = detail.statusCounts[status] ?: 0
            // 该状态一本书都没有就不占位；已选中的保留，否则取消不掉筛选
            if (count > 0 || status == selected) {
                ToggleChip(
                    label = "${stringResource(status.labelResId)} $count",
                    selected = status == selected,
                    onToggle = { onToggleFilter(status) },
                    compact = true,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}

@Composable
private fun AuthorDetailHeader(
    detail: AuthorDetailUi,
    modifier: Modifier = Modifier,
) {
    // 换成 secondaryContainer，跟下面的书籍卡片拉开层次
    NormalCard(
        modifier = modifier,
        containerColor = LegadoTheme.colorScheme.secondaryContainer,
        contentColor = LegadoTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (detail.avgRating > 0f) {
                AuthorRatingLabel(rating = detail.avgRating, starSize = 16.dp)
            }
            if (detail.bio.isNotBlank()) {
                AppText(
                    text = detail.bio,
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSecondaryContainer,
                )
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
        // AI 生成放标题栏操作槽，跟其它 AI 入口一致；生成中把按钮换成进度圈
        titleAction = {
            if (generating) {
                AppCircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                SmallTonalButton(
                    onClick = onGenerate,
                    icon = Icons.Default.AutoAwesome,
                    text = stringResource(R.string.author_bio_generate),
                )
            }
        },
        confirmText = stringResource(R.string.ok),
        onConfirm = { onSave(bio.trim()) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = onDismiss,
        content = {
            AppTextField(
                value = bio,
                onValueChange = onBioChange,
                placeholder = { AppText(stringResource(R.string.author_bio_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                enabled = !generating,
            )
        },
    )
}
