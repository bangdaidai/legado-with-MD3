package io.legado.app.ui.book.toc.rule.preview

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.ui.replace.ReplaceEditRoute
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.lazylist.FastScrollLazyColumn
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.progressIndicator.AppCircularProgressIndicator
import io.legado.app.ui.widget.components.rules.RuleEditFields
import io.legado.app.ui.widget.components.rules.RuleEditSheet
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TxtTocRulePreviewRouteScreen(
    bookUrl: String,
    currentTocRegex: String?,
    viewModel: TxtTocRulePreviewViewModel = koinViewModel(),
    onBack: () -> Unit,
    onApplyRule: (String) -> Unit,
    onOpenManagePage: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 编辑替换规则返回后刷新（网络书预览用）
    val editReplaceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onIntent(TxtTocRulePreviewIntent.Refresh)
        }
    }

    LaunchedEffect(bookUrl) {
        viewModel.init(bookUrl, currentTocRegex)
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TxtTocRulePreviewEffect.ShowToast -> context.toastOnUi(effect.message)
                is TxtTocRulePreviewEffect.OpenManagePage -> onOpenManagePage()
                is TxtTocRulePreviewEffect.ApplyRule -> onApplyRule(effect.rule)
                is TxtTocRulePreviewEffect.OpenReplaceRuleEditor -> {
                    editReplaceLauncher.launch(
                        ReplaceRuleActivity.startIntent(
                            context,
                            ReplaceEditRoute(id = effect.ruleId),
                        )
                    )
                }
            }
        }
    }

    TxtTocRulePreviewScreen(
        state = uiState,
        onIntent = viewModel::onIntent,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TxtTocRulePreviewScreen(
    state: TxtTocRulePreviewUiState,
    onIntent: (TxtTocRulePreviewIntent) -> Unit,
    onBack: () -> Unit,
) {
    // Chapter list bottom sheet
    when (val sheet = state.activeSheet) {
        is TxtTocRulePreviewSheet.ChapterList -> {
            val item = state.rules.firstOrNull { it.rule.id == sheet.item.rule.id } ?: sheet.item
            AppModalBottomSheet(
                data = item,
                onDismissRequest = { onIntent(TxtTocRulePreviewIntent.DismissSheet) },
                title = item.rule.name,
                startAction = {
                    TopBarActionButton(
                        onClick = { onIntent(TxtTocRulePreviewIntent.EditRule(item.rule)) },
                        imageVector = AppIcons.Edit,
                        contentDescription = stringResource(R.string.edit),
                    )
                },
                endAction = {
                    TopBarActionButton(
                        onClick = { onIntent(TxtTocRulePreviewIntent.ApplyRule) },
                        imageVector = AppIcons.Check,
                        contentDescription = stringResource(R.string.ok),
                    )
                },
            ) {
                ChapterListSheetContent(item = item)
            }
        }

        is TxtTocRulePreviewSheet.NetworkRuleChapters -> {
            AppModalBottomSheet(
                data = sheet.item,
                onDismissRequest = { onIntent(TxtTocRulePreviewIntent.DismissSheet) },
            ) {
                NetworkRuleChapterSheetContent(
                    item = sheet.item,
                    onEditRule = { onIntent(TxtTocRulePreviewIntent.EditNetworkRule(sheet.item.rule.id)) },
                )
            }
        }

        null -> { /* no sheet */ }
    }

    // Rule edit sheet
    state.editingRule?.let { rule ->
        RuleEditSheet(
            show = true,
            rule = rule,
            title = stringResource(R.string.txt_toc_rule),
            label1 = stringResource(R.string.chapter_rule),
            label2 = stringResource(R.string.example),
            label3 = stringResource(R.string.volume_rule),
            onDismissRequest = { onIntent(TxtTocRulePreviewIntent.DismissEditDialog) },
            onSave = { updatedRule ->
                onIntent(TxtTocRulePreviewIntent.SaveRule(updatedRule))
            },
            onCopy = { /* no-op */ },
            onPaste = { null },
            toFields = { r ->
                RuleEditFields(
                    name = r?.name ?: "",
                    rule1 = r?.chapterRule ?: "",
                    rule2 = r?.example ?: "",
                    rule3 = r?.volumeRule ?: ""
                )
            },
            fromFields = { fields, old ->
                old?.copy(
                    name = fields.name,
                    chapterRule = fields.rule1,
                    volumeRule = fields.rule3,
                    example = fields.rule2
                ) ?: TxtTocRule(
                    name = fields.name,
                    chapterRule = fields.rule1,
                    volumeRule = fields.rule3,
                    example = fields.rule2
                )
            }
        )
    }

    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { _ ->
            GlassMediumFlexibleTopAppBar(
                title = if (state.isTxt) stringResource(R.string.select_toc_rule)
                else stringResource(R.string.toc_rule_preview),
                subtitle = null,
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onBack) },
                actions = {
                    // Search button
                    TopBarActionButton(
                        onClick = { onIntent(TxtTocRulePreviewIntent.ToggleSearch) },
                        imageVector = AppIcons.Search,
                        contentDescription = stringResource(R.string.search),
                    )
                    if (state.isTxt) {
                        // Layout toggle button
                        TopBarActionButton(
                            onClick = { onIntent(TxtTocRulePreviewIntent.ToggleLayout) },
                            imageVector = if (state.isGridLayout) {
                                Icons.AutoMirrored.Outlined.FormatListBulleted
                            } else {
                                Icons.Default.GridView
                            },
                            contentDescription = stringResource(
                                if (state.isGridLayout) R.string.layout_mode_list else R.string.layout_mode_grid
                            ),
                        )
                        // Manage page button
                        TopBarActionButton(
                            onClick = { onIntent(TxtTocRulePreviewIntent.OpenManagePage) },
                            imageVector = AppIcons.Settings,
                            contentDescription = stringResource(R.string.manage),
                        )
                    } else {
                        // 网络书：跳转替换规则管理页
                        TopBarActionButton(
                            onClick = { onIntent(TxtTocRulePreviewIntent.OpenManagePage) },
                            imageVector = AppIcons.Settings,
                            contentDescription = stringResource(R.string.manage),
                        )
                    }
                },
                bottomContent = {
                    AnimatedVisibility(
                        modifier = Modifier.adaptiveHorizontalPadding(),
                        visible = state.showSearch,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        SearchBar(
                            query = state.searchQuery,
                            onQueryChange = { onIntent(TxtTocRulePreviewIntent.UpdateSearchQuery(it)) },
                            onSearch = {},
                            placeholder = stringResource(R.string.search),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (!state.isTxt) {
            // 网络书籍：标题替换规则预览
            NetworkRulePreviewContent(state = state, onIntent = onIntent, contentPadding = contentPadding)
        } else if (state.isGridLayout) {
                val displayRules = state.filteredRules
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = adaptiveContentPadding(
                        top = contentPadding.calculateTopPadding(),
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(displayRules, key = { _, item -> item.rule.id }) { _, item ->
                        RulePreviewCard(
                            item = item,
                            isSelected = item.rule.chapterRule == state.selectedRule,
                            onClick = {
                                onIntent(TxtTocRulePreviewIntent.SelectRule(item.rule.chapterRule))
                                onIntent(TxtTocRulePreviewIntent.ShowChapterList(item))
                            },
                        )
                    }
                }
            } else {
                val displayRules = state.filteredRules
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = adaptiveContentPadding(
                        top = contentPadding.calculateTopPadding(),
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(displayRules, key = { _, item -> item.rule.id }) { _, item ->
                        RulePreviewListItem(
                            item = item,
                            isSelected = item.rule.chapterRule == state.selectedRule,
                            onClick = {
                                onIntent(TxtTocRulePreviewIntent.SelectRule(item.rule.chapterRule))
                                onIntent(TxtTocRulePreviewIntent.ShowChapterList(item))
                            },
                        )
                }
            }
        }
    }
}

@Composable
private fun NetworkRuleCard(
    item: NetworkRulePreviewItem,
    onClick: () -> Unit,
    onEdit: () -> Unit,
) {
    GlassCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AppText(
                    text = if (item.order > 0) "${item.order}. ${item.rule.name}" else item.rule.name,
                    style = LegadoTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.example?.let { example ->
                    Spacer(Modifier.height(2.dp))
                    AppText(
                        text = example,
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            if (!item.computed) {
                AppCircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (item.matchCount > 0)
                        LegadoTheme.colorScheme.primaryContainer
                    else
                        LegadoTheme.colorScheme.surfaceVariant,
                ) {
                    AppText(
                        text = if (item.matchCount > 0)
                            stringResource(R.string.toc_preview_matched, item.matchCount)
                        else
                            stringResource(R.string.toc_preview_no_match),
                        style = LegadoTheme.typography.labelSmall,
                        color = if (item.matchCount > 0)
                            LegadoTheme.colorScheme.onPrimaryContainer
                        else
                            LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            TopBarActionButton(
                onClick = onEdit,
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.edit),
            )
        }
    }
}

@Composable
private fun ChainDemoCard(demo: ChainDemo) {
    var expanded by remember { mutableStateOf(false) }
    GlassCard(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AppText(
                        text = stringResource(R.string.toc_preview_chain_title),
                        style = LegadoTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(2.dp))
                    AppText(
                        text = stringResource(R.string.toc_preview_chain_summary, demo.steps.size, demo.changedStepCount),
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TopBarActionButton(
                    onClick = { expanded = !expanded },
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.toc_preview_chain_title),
                )
            }
            Spacer(Modifier.height(4.dp))
            AppText(
                text = demo.originalTitle,
                style = LegadoTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                text = "→ ${demo.finalTitle}",
                style = LegadoTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    demo.steps.forEachIndexed { index, step ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (step.changed) LegadoTheme.colorScheme.primaryContainer
                                else LegadoTheme.colorScheme.surfaceVariant,
                            ) {
                                AppText(
                                    text = "${index + 1}",
                                    style = LegadoTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            AppText(
                                text = step.ruleName,
                                style = LegadoTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (step.changed) {
                                AppText(
                                    text = step.after,
                                    style = LegadoTheme.typography.bodySmall,
                                    color = LegadoTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkRuleChapterSheetContent(
    item: NetworkRulePreviewItem,
    onEditRule: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AppText(
                text = item.rule.name,
                style = LegadoTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(8.dp))
            TopBarActionButton(
                onClick = onEditRule,
                imageVector = Icons.Default.Edit,
                contentDescription = stringResource(R.string.edit),
            )
        }
        Spacer(Modifier.height(4.dp))
        AppText(
            text = stringResource(R.string.toc_preview_matched, item.matchCount),
            style = LegadoTheme.typography.bodyMedium,
            color = LegadoTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (item.chapters.isEmpty()) {
            AppText(
                text = stringResource(R.string.toc_preview_no_match),
                style = LegadoTheme.typography.bodyMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FastScrollLazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
            ) {
                itemsIndexed(item.chapters.toList()) { _, (origin, display) ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        AppText(text = display, style = LegadoTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        AppText(text = origin, style = LegadoTheme.typography.bodySmall, color = LegadoTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun RulePreviewCard(
    item: TocRulePreviewItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) LegadoTheme.colorScheme.primary else Color.Transparent,
        label = "borderColor"
    )

    GlassCard(
        onClick = onClick,
        border = if (isSelected) {
            BorderStroke(1.5.dp, LegadoTheme.colorScheme.primary)
        } else {
            BorderStroke(0.5.dp, borderColor)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .padding(bottom = 28.dp),
            ) {
                AppText(
                    text = item.rule.name,
                    style = LegadoTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                item.rule.example?.let { example ->
                    AppText(
                        text = example,
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = LegadoTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
            ) {
                if (item.totalCount < 0) {
                    AppCircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .padding(4.dp)
                    )
                } else {
                    AppText(
                        text = stringResource(R.string.chapter_count_format, item.totalCount),
                        style = LegadoTheme.typography.labelSmall,
                        color = LegadoTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RulePreviewListItem(
    item: TocRulePreviewItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) LegadoTheme.colorScheme.primary else Color.Transparent,
        label = "borderColor"
    )

    GlassCard(
        onClick = onClick,
        border = if (isSelected) {
            BorderStroke(1.5.dp, LegadoTheme.colorScheme.primary)
        } else {
            BorderStroke(0.5.dp, borderColor)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = item.rule.name,
                    style = LegadoTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.rule.example?.let { example ->
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = example,
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = LegadoTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
            ) {
                if (item.totalCount < 0) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(16.dp)
                            .padding(4.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = stringResource(
                            R.string.chapter_count_format,
                            item.totalCount
                        ),
                        style = LegadoTheme.typography.labelSmall,
                        color = LegadoTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChapterListSheetContent(
    item: TocRulePreviewItem,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.chapter_count_format, item.totalCount),
            style = LegadoTheme.typography.bodyMedium,
            color = LegadoTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Chapter list
        FastScrollLazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp),
        ) {
            itemsIndexed(item.chapters.toList()) { _, chapter ->
                Text(
                    text = chapter,
                    style = LegadoTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.chapters.size >= 500) {
                item {
                    Text(
                        text = stringResource(R.string.chapter_list_preview_limit),
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

// ===================== 网络书籍：标题替换规则预览 =====================

@Composable
private fun NetworkRulePreviewContent(
    state: TxtTocRulePreviewUiState,
    onIntent: (TxtTocRulePreviewIntent) -> Unit,
    contentPadding: PaddingValues,
) {
    if (state.emptyHint.isNotEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            AppText(
                text = state.emptyHint,
                style = LegadoTheme.typography.bodyMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val rules = state.filteredNetworkRules
    FastScrollLazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = adaptiveContentPadding(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        item(key = "network_header") {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                AppText(
                    text = stringResource(R.string.toc_preview_chapter_count, state.chapterTotal),
                    style = LegadoTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                AppText(
                    text = stringResource(
                        R.string.toc_preview_rule_count,
                        state.titleReplaceRuleCount
                    ),
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                AppText(
                    text = stringResource(R.string.toc_preview_network_tip),
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.chainDemo?.let { demo ->
            item(key = "chain_demo") { ChainDemoCard(demo = demo) }
        }

        if (rules.isEmpty()) {
            item(key = "network_empty") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppText(
                        text = stringResource(R.string.toc_preview_no_title_rule),
                        style = LegadoTheme.typography.bodyMedium,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            itemsIndexed(rules, key = { _, item -> item.rule.id }) { _, item ->
                NetworkRuleCard(
                    item = item,
                    onClick = { onIntent(TxtTocRulePreviewIntent.ShowNetworkRuleChapters(item)) },
                    onEdit = { onIntent(TxtTocRulePreviewIntent.EditNetworkRule(item.rule.id)) },
                )
            }
        }
    }
}
