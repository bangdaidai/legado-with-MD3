package io.legado.app.ui.main.my.authorManage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.domain.model.BookSearchScope
import io.legado.app.ui.book.readingmemory.MemoryBookCard
import io.legado.app.ui.book.search.ScopeSelectSheet
import io.legado.app.ui.main.bookshelf.BookshelfListItem
import io.legado.app.ui.widget.components.card.TagChip
import io.legado.app.ui.widget.components.card.TagChipSize
import io.legado.app.ui.widget.components.image.cover.CoilBookCover
import io.legado.app.utils.HtmlFormatter
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.book.SearchBookPreviewSheet
import io.legado.app.ui.widget.components.button.series.SmallTonalButton
import io.legado.app.ui.widget.components.progressIndicator.AppCircularProgressIndicator
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import kotlinx.collections.immutable.ImmutableList

@Composable
fun AuthorDetailScreen(
    uiState: AuthorDetailUiState,
    onIntent: (AuthorDetailIntent) -> Unit,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenSearchBook: (SearchBook) -> Unit,
) {
    val detail = uiState.detail
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    var showScopeSheet by remember { mutableStateOf(false) }
    var previewBook by remember { mutableStateOf<SearchBook?>(null) }
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
                contentPadding = adaptiveContentPadding(
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // 评分和简介都没有时整张卡是空的，直接不渲染
                if (detail.avgRating > 0f || detail.bio.isNotBlank()) {
                    item {
                        AuthorDetailHeader(
                            detail = detail,
                            onClick = { onIntent(AuthorDetailIntent.ToggleEditBio) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
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
                            // 12dp + 卡片自带的 4dp = 分区之间 16dp，与标签管理页同节奏
                            .padding(vertical = 12.dp),
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
                item {
                    OtherWorksHeader(
                        scopeNames = uiState.worksScopeNames,
                        searching = uiState.works is AuthorWorksState.Searching,
                        onRefresh = { onIntent(AuthorDetailIntent.RefreshWorks) },
                        onStop = { onIntent(AuthorDetailIntent.StopWorksSearch) },
                        onPickScope = { showScopeSheet = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    )
                }
                when (val works = uiState.works) {
                    AuthorWorksState.Idle -> item {
                        WorksHint(stringResource(R.string.author_works_idle))
                    }

                    is AuthorWorksState.Searching -> item {
                        WorksHint(
                            stringResource(
                                R.string.author_works_searching,
                                works.processed,
                                works.total,
                            )
                        )
                    }

                    AuthorWorksState.Empty -> item {
                        WorksHint(stringResource(R.string.author_works_empty))
                    }

                    is AuthorWorksState.Error -> item {
                        WorksHint(stringResource(R.string.author_works_error, works.message))
                    }

                    is AuthorWorksState.Success -> items(
                        works.books,
                        key = { it.book.bookUrl },
                    ) { item ->
                        // 与上方「关联书籍」用同一个 BookshelfListItem 卡片，背景、行距、标签口径完全一致
                        BookshelfListItem(
                            settings = uiState.bookshelfSettings,
                            isCompact = false,
                            coverWidth = uiState.coverWidth,
                            cover = { m ->
                                CoilBookCover(
                                    name = item.book.name.takeIf { it.isNotBlank() },
                                    author = item.book.author.takeIf { it.isNotBlank() },
                                    path = item.book.coverUrl?.takeIf { it.isNotBlank() },
                                    radius = 8.dp,
                                    modifier = m.fillMaxSize(),
                                )
                            },
                            title = item.book.name,
                            titleMaxLines = uiState.bookshelfSettings.bookshelfTitleMaxLines,
                            columnContent = {
                                // 该区块已是「某作者的其他作品」，作者名多余，不显示副标题
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier
                                        .horizontalScroll(rememberScrollState())
                                        .padding(vertical = 4.dp),
                                ) {
                                    item.tags.forEach { tag ->
                                        TagChip(
                                            tag = tag,
                                            color = null,
                                            size = TagChipSize.Small,
                                            showColoredBorder = uiState.bookshelfSettings.bookshelfTagBorder,
                                        )
                                    }
                                }
                                val intro = remember(item.book.intro) {
                                    item.book.intro?.takeIf { it.isNotBlank() }
                                        ?.let { HtmlFormatter.formatSummaryText(it) }
                                }
                                if (intro != null) {
                                    AppText(
                                        text = intro,
                                        style = LegadoTheme.typography.bodySmall,
                                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                    )
                                }
                            },
                            onClick = { onOpenSearchBook(item.book) },
                            onLongClick = { previewBook = item.book },
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

    // 复用搜索页的范围选择弹窗，草稿模式：选完点勾才提交
    val worksScope = BookSearchScope(uiState.worksScopeRaw)
    ScopeSelectSheet(
        show = showScopeSheet,
        onDismissRequest = { showScopeSheet = false },
        isAll = worksScope.isAll,
        onSelectAll = {
            onIntent(AuthorDetailIntent.ApplyWorksScope(emptyList(), emptyList(), false))
        },
        groups = uiState.enabledGroups,
        selectedGroups = worksScope.groupNames,
        onToggleGroup = {},
        sources = uiState.enabledSources,
        selectedSources = worksScope.sourceUrls,
        onToggleSource = {},
        isSourceScope = worksScope.isSource,
        title = stringResource(R.string.author_works_scope),
        onApplyScope = { selection ->
            onIntent(
                AuthorDetailIntent.ApplyWorksScope(
                    groupNames = selection.groupNames,
                    sources = selection.sources,
                    isSourceScope = selection.isSourceScope,
                )
            )
            showScopeSheet = false
        },
    )

    SearchBookPreviewSheet(
        data = previewBook,
        onDismissRequest = { previewBook = null },
        onOpenDetail = { book, _ ->
            previewBook = null
            onOpenSearchBook(book)
        },
        onAddToShelf = { book ->
            previewBook = null
            onIntent(AuthorDetailIntent.AddWorkToBookshelf(book))
        },
    )
}

/**
 * 「其他作品」分区标题：范围按钮 + 搜索/停止按钮。
 * 不自动联网，必须点搜索按钮才发请求。
 * 结构与「关联书籍」那行一致：标题在左，控件紧跟标题，放不下就折行。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OtherWorksHeader(
    scopeNames: ImmutableList<String>,
    searching: Boolean,
    onRefresh: () -> Unit,
    onStop: () -> Unit,
    onPickScope: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AppText(
            text = stringResource(R.string.author_other_works),
            style = LegadoTheme.typography.titleMedium,
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .padding(end = 2.dp),
        )
        SmallTonalButton(
            onClick = onPickScope,
            text = scopeNames.takeIf { it.isNotEmpty() }?.joinToString("/")
                ?: stringResource(R.string.author_works_scope_all),
            // 与右侧图标按钮同高：文本按钮按内容定高，图标按钮是 32dp 方形，这里统一锁 32dp
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .height(32.dp),
        )
        if (searching) {
            SmallTonalButton(
                onClick = onStop,
                icon = Icons.Default.Close,
                contentDescription = stringResource(R.string.author_works_stop),
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .height(32.dp),
            )
        } else {
            SmallTonalButton(
                onClick = onRefresh,
                icon = Icons.Default.Search,
                contentDescription = stringResource(R.string.author_works_search),
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .height(32.dp),
            )
        }
    }
}

/** 其他作品的空/进度/错误提示，占位样式统一。 */
@Composable
private fun WorksHint(text: String) {
    AppText(
        text = text,
        style = LegadoTheme.typography.bodySmall,
        color = LegadoTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    )
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
            // 与「其他作品」那行的按钮同一个组件，保证两行样式一致
            if (count > 0 || status == selected) {
                SmallTonalButton(
                    onClick = { onToggleFilter(status) },
                    selected = status == selected,
                    text = "${stringResource(status.labelResId)} $count",
                    // 与「其他作品」那行的按钮同高，统一锁 32.dp
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .height(32.dp),
                )
            }
        }
    }
}

@Composable
private fun AuthorDetailHeader(
    detail: AuthorDetailUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 用主题自己的卡片底色，不跟书架的自定义卡片色，这样和下面的书籍卡片能分层
    NormalCard(
        modifier = modifier,
        containerColor = LegadoTheme.colorScheme.cardContainer,
        // 点整张卡等于点标题栏的编辑按钮
        onClick = onClick,
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
