package io.legado.app.ui.book.tagmanage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.LocalAppUiConfiguration
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.dialog.ColorPickerSheet
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.menuItem.MenuItemIcon
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagManagementScreen(
    state: TagManagementUiState,
    onIntent: (TagManagementIntent) -> Unit,
    onBack: () -> Unit,
    onNavigateToExcludedTag: () -> Unit,
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    var showSearch by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    // 编辑/弹窗状态
    var tagEdit by remember { mutableStateOf<TagEditData?>(null) }
    var showColorPicker by remember { mutableStateOf(false) }
    var groupEdit by remember { mutableStateOf<BookTagGroup?>(null) }
    var showGroupManage by remember { mutableStateOf(false) }
    var showMappingManage by remember { mutableStateOf(false) }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "标签管理",
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                actions = {
                    TopBarActionButton(
                        onClick = {
                            showSearch = !showSearch
                            if (!showSearch) onIntent(TagManagementIntent.Search(""))
                        },
                        imageVector = AppIcons.Search,
                        contentDescription = "搜索",
                    )
                    TopBarActionButton(
                        onClick = { menuExpanded = true },
                        imageVector = AppIcons.MoreVert,
                        contentDescription = "更多",
                    )
                    RoundDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) { dismiss ->
                        RoundDropdownMenuItem(
                            text = "新增标签",
                            leadingIcon = { MenuItemIcon(Icons.Filled.Label) },
                            onClick = { dismiss(); tagEdit = TagEditData() },
                        )
                        RoundDropdownMenuItem(
                            text = "分组管理",
                            leadingIcon = { MenuItemIcon(Icons.Filled.Folder) },
                            onClick = { dismiss(); showGroupManage = true },
                        )
                        RoundDropdownMenuItem(
                            text = stringResource(R.string.tag_mapping),
                            leadingIcon = { MenuItemIcon(Icons.Filled.SwapHoriz) },
                            onClick = { dismiss(); showMappingManage = true },
                        )
                        RoundDropdownMenuItem(
                            text = "排除标签",
                            leadingIcon = { MenuItemIcon(Icons.Filled.Block) },
                            onClick = { dismiss(); onNavigateToExcludedTag() },
                        )
                    }
                },
                bottomContent = {
                    AnimatedVisibility(
                        modifier = Modifier.adaptiveHorizontalPadding(),
                        visible = showSearch,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        SearchBar(
                            query = state.searchQuery,
                            onQueryChange = { onIntent(TagManagementIntent.Search(it)) },
                            placeholder = stringResource(R.string.search_tag),
                        )
                    }
                },
            )
        },
    ) { padding ->
        TagListTab(
            state = state,
            onIntent = onIntent,
            topContentPadding = padding.calculateTopPadding(),
            bottomContentPadding = padding.calculateBottomPadding(),
        )
    }

    // 标签编辑
    TagEditSheet(
        data = tagEdit,
        groups = state.groups,
        onChange = { tagEdit = it },
        onConfirm = {
            onIntent(TagManagementIntent.SaveTag(it.id, it.name, it.groupId, it.color))
            tagEdit = null
        },
        onPickColor = { showColorPicker = true },
        onExclude = { name ->
            onIntent(TagManagementIntent.ExcludeTag(name))
            tagEdit = null
        },
        onDismiss = { tagEdit = null },
    )

    ColorPickerSheet(
        show = showColorPicker && tagEdit != null,
        initialColor = (tagEdit?.color ?: 0xFF6200EE).toInt(),
        onColorSelected = { c -> tagEdit = tagEdit?.copy(color = c.toUInt().toLong()) },
        onDismissRequest = { showColorPicker = false },
    )

    // 分组管理弹窗（溢出菜单打开）
    GroupManageSheet(
        show = showGroupManage,
        groups = state.groups,
        tagCounts = state.groupTagCounts,
        onDismissRequest = { showGroupManage = false },
        onAdd = { groupEdit = BookTagGroup(name = ""); showGroupManage = false },
        onUpdateGroup = { group, newName ->
            onIntent(TagManagementIntent.SaveGroup(group.id, newName.trim()))
        },
        onDelete = { onIntent(TagManagementIntent.DeleteGroup(it)) },
        onReorder = { onIntent(TagManagementIntent.ReorderGroups(it)) },
    )
    GroupEditSheet(
        data = groupEdit,
        onSave = { onIntent(TagManagementIntent.SaveGroup(it.id, it.name)); groupEdit = null },
        onDelete = { onIntent(TagManagementIntent.DeleteGroup(it)); groupEdit = null },
        onDismiss = { groupEdit = null },
    )

    // 标签映射弹窗（溢出菜单打开，仅查看/删除；新增映射请在标签编辑对话框中添加）
    MappingManageSheet(
        show = showMappingManage,
        mappings = state.mappings,
        tags = state.tags,
        onDismissRequest = { showMappingManage = false },
        onDelete = { onIntent(TagManagementIntent.DeleteMapping(it)); showMappingManage = false },
    )
}

/* ---------------- 标签列表（流式，按分组分区） ---------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagListTab(
    state: TagManagementUiState,
    onIntent: (TagManagementIntent) -> Unit,
    topContentPadding: Dp = 0.dp,
    bottomContentPadding: Dp = 0.dp,
) {
    val query = state.searchQuery
    val nonEmpty = state.tags.filter { (state.tagCounts[it.id] ?: 0) > 0 }
    val filtered = if (query.isBlank()) {
        nonEmpty
    } else {
        nonEmpty.filter { it.name.contains(query, ignoreCase = true) }
    }

    val groups = state.groups
    val grouped = filtered.filter { it.groupId != 0L }.groupBy { it.groupId }
    val ungrouped = filtered.filter { it.groupId == 0L }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = topContentPadding + 16.dp, bottom = bottomContentPadding + 16.dp
        ),
    ) {
        if (ungrouped.isNotEmpty()) {
            item(key = "section_ungrouped") {
                Text("未分组", style = LegadoTheme.typography.titleMedium, color = LegadoTheme.colorScheme.onSurface)
            }
            item(key = "ungrouped") {
                TagChipRow(ungrouped, state, onIntent)
            }
        }
        groups.forEach { group ->
            val items = grouped[group.id].orEmpty()
            if (items.isNotEmpty()) {
                item(key = "section_${group.id}") {
                    Text(group.name, style = LegadoTheme.typography.titleMedium, color = LegadoTheme.colorScheme.onSurface)
                }
                item(key = "group_${group.id}") {
                    TagChipRow(items, state, onIntent)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChipRow(
    tags: List<BookTag>,
    state: TagManagementUiState,
    onIntent: (TagManagementIntent) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val themeSettings = LocalAppUiConfiguration.current.theme
        val resolvedCornerRadius = if (themeSettings.overrideBaseCardCornerRadius) {
            themeSettings.baseCardCornerRadius.dp
        } else {
            8.dp
        }
        val resolvedShape = RoundedCornerShape(resolvedCornerRadius)
        tags.forEach { tag ->
            val color = if (tag.color != 0L) Color(tag.color.toInt()) else LegadoTheme.colorScheme.primary
            val count = state.tagCounts[tag.id] ?: 0
            val borderModifier = if (state.bookshelfTagBorder) {
                Modifier.border(BorderStroke(0.5.dp, color), resolvedShape)
            } else if (themeSettings.overrideBaseCardBorder) {
                val configuredColor = if (LegadoTheme.isDark) {
                    themeSettings.baseCardBorderColorNight
                } else {
                    themeSettings.baseCardBorderColor
                }
                val borderColor = configuredColor.takeIf { it != 0 }?.let(::Color)
                    ?: LegadoTheme.colorScheme.outlineVariant
                Modifier.border(
                    BorderStroke(themeSettings.baseCardBorderWidth.dp, borderColor),
                    resolvedShape
                )
            } else {
                Modifier
            }
            Row(
                modifier = Modifier
                    .clip(resolvedShape)
                    .background(color.copy(alpha = 0.14f))
                    .then(borderModifier)
                    .clickable { onIntent(TagManagementIntent.OpenTagDetail(tag.id)) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(color),
                )
                Text(
                    tag.name,
                    style = LegadoTheme.typography.labelMedium,
                    color = color,
                )
                if (count > 0) {
                    Text(
                        "$count",
                        style = LegadoTheme.typography.labelSmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
