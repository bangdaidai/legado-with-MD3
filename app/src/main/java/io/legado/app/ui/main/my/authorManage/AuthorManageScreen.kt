package io.legado.app.ui.main.my.authorManage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.StarRate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.book.readingmemory.detail.ReadingMemoryRatingBar
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton

/** 顶栏排序按钮的图标随当前排序方式变化，单击循环切换。 */
private val AuthorSort.icon: ImageVector
    get() = when (this) {
        AuthorSort.BookCount -> Icons.Filled.FormatListNumbered
        AuthorSort.Rating -> Icons.Filled.StarRate
        AuthorSort.Name -> Icons.Filled.SortByAlpha
    }

private val AuthorSort.labelResId: Int
    get() = when (this) {
        AuthorSort.BookCount -> R.string.author_sort_book_count
        AuthorSort.Rating -> R.string.author_sort_rating
        AuthorSort.Name -> R.string.author_sort_name
    }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AuthorManageScreen(
    uiState: AuthorManageUiState,
    onIntent: (AuthorManageIntent) -> Unit,
    onBack: () -> Unit,
    onClickAuthor: (String) -> Unit,
) {
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    LaunchedEffect(uiState.sortBy) {
        runCatching { listState.scrollToItem(0) }
    }
    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.author_management),
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                actions = {
                    TopBarActionButton(
                        onClick = {
                            searchActive = !searchActive
                            // 关掉搜索时清空关键词，避免搜索框消失了列表还在被过滤
                            if (!searchActive) onIntent(AuthorManageIntent.SetSearchQuery(""))
                        },
                        imageVector = if (searchActive) {
                            Icons.Filled.SearchOff
                        } else {
                            Icons.Filled.Search
                        },
                        contentDescription = stringResource(
                            if (searchActive) R.string.close else R.string.search
                        ),
                    )
                    TopBarActionButton(
                        onClick = { onIntent(AuthorManageIntent.SetSort(uiState.sortBy.next())) },
                        imageVector = uiState.sortBy.icon,
                        contentDescription = stringResource(uiState.sortBy.labelResId),
                    )
                },
                bottomContent = {
                    AnimatedVisibility(
                        modifier = Modifier.adaptiveHorizontalPadding(),
                        visible = searchActive,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        SearchBar(
                            query = uiState.searchQuery,
                            onQueryChange = { onIntent(AuthorManageIntent.SetSearchQuery(it)) },
                            placeholder = stringResource(R.string.search),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        when {
            uiState.loading -> EmptyMessage(
                message = "",
                isLoading = true,
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )

            uiState.authors.isEmpty() -> EmptyMessage(
                message = stringResource(
                    if (uiState.searchQuery.isBlank()) {
                        R.string.author_management_empty
                    } else {
                        R.string.author_search_empty
                    }
                ),
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )

            else -> LazyColumn(
                state = listState,
                contentPadding = adaptiveContentPadding(
                    top = contentPadding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val authors = uiState.authors
                val showIndex = uiState.sortBy == AuthorSort.Name
                authors.forEachIndexed { index, author ->
                    val newSection = index == 0 ||
                            authors[index - 1].indexLabel != author.indexLabel
                    if (showIndex && newSection) {
                        stickyHeader(key = "index_${author.indexLabel}") {
                            AuthorIndexHeader(label = author.indexLabel)
                        }
                    }
                    item(key = author.name) {
                        AuthorCard(
                            author = author,
                            onClick = { onClickAuthor(author.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthorIndexHeader(label: String) {
    AppText(
        text = label,
        style = LegadoTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = LegadoTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(LegadoTheme.colorScheme.surface)
            .padding(vertical = 6.dp),
    )
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
                    AuthorRatingLabel(rating = author.avgRating, starSize = 14.dp)
                }
            }
            if (author.bio.isNotBlank()) {
                AppText(
                    text = author.bio,
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
            AppText(
                text = stringResource(R.string.author_read_count, author.readBookCount)
                        + " · " + stringResource(R.string.author_book_count, author.bookCount),
                style = LegadoTheme.typography.labelSmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 星条 + 平均分数值。作者列表和作者页共用，两处评分展示保持一致。 */
@Composable
internal fun AuthorRatingLabel(
    rating: Float,
    starSize: Dp,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        ReadingMemoryRatingBar(
            rating = rating,
            onRatingChanged = {},
            enabled = false,
            starSize = starSize,
        )
        AppText(
            text = "%.1f".format(rating),
            style = LegadoTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = LegadoTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}


