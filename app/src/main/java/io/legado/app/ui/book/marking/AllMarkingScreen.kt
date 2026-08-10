package io.legado.app.ui.book.marking

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.ui.book.read.sheet.MarkingSheet
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.LocalAppUiConfiguration
import io.legado.app.ui.theme.ThemeResolver
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.shareCard.ShareCardPreviewSheet
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.card.TextCard
import io.legado.app.ui.widget.components.card.TicketNotchDivider
import io.legado.app.ui.widget.components.lazylist.FastScrollLazyColumn
import io.legado.app.ui.widget.components.list.TopFloatingStickyItem
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.legado.app.ui.book.read.MarkingUiState as ReaderMarkingUiState

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun AllMarkingRouteScreen(
    viewModel: AllMarkingViewModel = koinViewModel(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.onIntent(AllMarkingIntent.Export(it, isMarkdown = true))
            Toast.makeText(context, context.getString(R.string.export_started), Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is AllMarkingEffect.ShowMessage ->
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    AllMarkingScreen(
        state = uiState,
        onIntent = viewModel::onIntent,
        onBack = onBack,
        onRequestExport = { exportLauncher.launch(null) },
    )
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun AllMarkingScreen(
    state: MarkingUiState,
    onIntent: (AllMarkingIntent) -> Unit,
    onBack: () -> Unit,
    onRequestExport: () -> Unit = {},
) {
    val contentState = when {
        state.isLoading -> "LOADING"
        state.markings.isEmpty() -> "EMPTY"
        else -> "CONTENT"
    }
    val searchText = state.searchQuery
    val collapsedGroups = state.collapsedGroups
    val markingsGrouped = state.markings
    val markingGroups = remember(markingsGrouped) { markingsGrouped.entries.toList() }
    val allKeys = markingsGrouped.keys
    val isAllCollapsed =
        allKeys.isNotEmpty() && allKeys.all { collapsedGroups.contains(it.toString()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val isMiuix = ThemeResolver.isMiuixEngine(LegadoTheme.composeEngine)
    val ticketThemeSettings = LocalAppUiConfiguration.current.theme
    val ticketBorderColor = (if (LegadoTheme.isDark) {
        ticketThemeSettings.baseCardBorderColorNight
    } else {
        ticketThemeSettings.baseCardBorderColor
    }).takeIf { it != 0 }?.let(::Color) ?: LegadoTheme.colorScheme.outlineVariant
    val ticketStrokeWidth = ticketThemeSettings.baseCardBorderWidth.dp
    val stickyGroup by remember(markingGroups, collapsedGroups, listState) {
        derivedStateOf {
            val firstVisibleIndex = listState.firstVisibleItemIndex
            val firstVisibleGroup = markingGroups.getOrNull(firstVisibleIndex)
                ?: return@derivedStateOf null
            val isCollapsed = collapsedGroups.contains(firstVisibleGroup.key.toString())
            val shouldStick = firstVisibleIndex > 0 || listState.firstVisibleItemScrollOffset > 24
            if (!isCollapsed && shouldStick) {
                firstVisibleGroup.key
            } else {
                null
            }
        }
    }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.all_marking),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBack)
                },
                actions = {
                    if (markingsGrouped.isNotEmpty()) {
                        TopBarActionButton(
                            onClick = { onIntent(AllMarkingIntent.ToggleAllCollapse(allKeys)) },
                            imageVector = if (isAllCollapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                            contentDescription = stringResource(
                                if (isAllCollapsed) {
                                    R.string.a11y_expand_all_bookmark_groups
                                } else {
                                    R.string.a11y_collapse_all_bookmark_groups
                                }
                            )
                        )
                    }
                    TopBarActionButton(
                        onClick = onRequestExport,
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = stringResource(R.string.export_markings)
                    )
                    TopBarActionButton(
                        onClick = {
                            showSearch = !showSearch
                            if (!showSearch) {
                                onIntent(AllMarkingIntent.SetSearchQuery(""))
                            }
                        },
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search)
                    )
                },
                bottomContent = {
                    AnimatedVisibility(
                        modifier = Modifier.adaptiveHorizontalPadding(),
                        visible = showSearch,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        SearchBar(
                            query = searchText,
                            onQueryChange = { onIntent(AllMarkingIntent.SetSearchQuery(it)) },
                            placeholder = stringResource(R.string.search),
                            scrollState = listState,
                            scope = scope
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        AnimatedContent(
            targetState = contentState,
            label = "markingTransition"
        ) { target ->
            when (target) {
                "LOADING" -> {
                    EmptyMessage(
                        message = stringResource(R.string.loading),
                        isLoading = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = paddingValues.calculateTopPadding(), bottom = 120.dp)
                    )
                }

                "EMPTY" -> {
                    EmptyMessage(
                        message = stringResource(R.string.marks_empty),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = paddingValues.calculateTopPadding(), bottom = 120.dp)
                    )
                }

                "CONTENT" -> {
                    Box(modifier = Modifier.fillMaxSize()) {
                        FastScrollLazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = adaptiveContentPadding(
                                top = paddingValues.calculateTopPadding(),
                                bottom = 120.dp
                            )
                        ) {
                            items(
                                items = markingGroups,
                                key = { it.key.toString() }
                            ) { (headerKey, markings) ->
                                val isCollapsed = collapsedGroups.contains(headerKey.toString())
                                GlassCard(
                                    modifier = Modifier
                                        .animateItem()
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    cornerRadius = 12.dp,
                                    containerColor = LegadoTheme.colorScheme.surfaceContainer
                                ) {
                                    MarkingGroupHeaderContent(
                                        title = headerKey.bookName,
                                        subtitle = headerKey.bookAuthor,
                                        isCollapsed = isCollapsed,
                                        onToggle = { onIntent(AllMarkingIntent.ToggleGroupCollapse(headerKey)) },
                                        isMiuix = isMiuix
                                    )
                                    AnimatedVisibility(visible = !isCollapsed && markings.isNotEmpty()) {
                                        Column {
                                            HorizontalDivider(color = LegadoTheme.colorScheme.surface)
                                            markings.forEachIndexed { index, item ->
                                                if (index > 0) {
                                                    TicketNotchDivider(
                                                        color = ticketBorderColor,
                                                        strokeWidth = ticketStrokeWidth,
                                                    )
                                                }
                                                MarkingRow(
                                                    item = item,
                                                    modifier = Modifier.fillMaxWidth(),
                                                    onClick = {
                                                        onIntent(AllMarkingIntent.OpenEdit(item.id))
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        TopFloatingStickyItem(
                            item = stickyGroup,
                            modifier = Modifier.padding(
                                top = paddingValues.calculateTopPadding() + 4.dp,
                                start = 8.dp
                            )
                        ) { group ->
                            TextCard(
                                text = group.bookName,
                                textStyle = LegadoTheme.typography.labelLarge,
                                cornerRadius = 8.dp,
                                horizontalPadding = 8.dp,
                                verticalPadding = 6.dp,
                                onClick = {
                                    scope.launch {
                                        val index = markingGroups.indexOfFirst { it.key == group }
                                        if (index >= 0) listState.animateScrollToItem(index)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    state.editing?.let { marking ->
        MarkingSheet(
            show = true,
            state = ReaderMarkingUiState(editing = marking),
            onDismissRequest = { onIntent(AllMarkingIntent.CloseEdit) },
            onSave = { _, note ->
                onIntent(AllMarkingIntent.SaveMarkingNote(marking.id, note))
            },
            onDelete = {
                onIntent(AllMarkingIntent.DeleteMarking(marking.id))
                onIntent(AllMarkingIntent.CloseEdit)
            },
            showStyleConfig = false,
            onGenerateShareCard = {
                onIntent(AllMarkingIntent.CloseEdit)
                onIntent(AllMarkingIntent.GenerateShareCard(marking))
            },
        )
    }

    ShareCardPreviewSheet(
        show = state.showShareCard,
        data = state.shareCardData,
        initialBitmap = state.shareCardBitmap,
        loading = state.shareCardLoading,
        onDismissRequest = { onIntent(AllMarkingIntent.DismissShareCard) },
    )
}

@Composable
private fun MarkingRow(
    item: MarkingItemUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item.chapterName?.let {
            AppText(
                text = it,
                style = LegadoTheme.typography.labelMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item.textSnippet?.let {
            AppText(
                text = it,
                style = LegadoTheme.typography.bodyMedium,
                color = LegadoTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        item.note?.let {
            AppText(
                text = it,
                style = LegadoTheme.typography.bodyMedium,
                color = LegadoTheme.colorScheme.primary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MarkingGroupHeaderContent(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String?,
    isCollapsed: Boolean,
    onToggle: () -> Unit,
    isMiuix: Boolean
) {
    val contentColor by animateColorAsState(
        if (isMiuix) MiuixTheme.colorScheme.primary else MaterialTheme.colorScheme.primary,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "CardColor"
    )
    val headerDescription = listOfNotNull(title, subtitle).joinToString()
    val headerStateDescription = stringResource(
        if (isCollapsed) R.string.a11y_collapsed else R.string.a11y_expanded
    )
    val clickLabel = stringResource(
        if (isCollapsed) R.string.expand else R.string.collapse
    )

    ListItem(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                contentDescription = headerDescription
                stateDescription = headerStateDescription
                onClick(label = clickLabel, action = null)
            }
            .combinedClickable(onClick = onToggle),
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent,
            supportingColor = LegadoTheme.colorScheme.onSurfaceVariant,
            trailingIconColor = LegadoTheme.colorScheme.onSurfaceVariant
        ),
        headlineContent = {
            AppText(
                text = title,
                style = LegadoTheme.typography.titleMedium,
                color = contentColor
            )
        },
        supportingContent = {
            subtitle?.let {
                AppText(
                    text = it,
                    style = LegadoTheme.typography.labelMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}



