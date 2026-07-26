package io.legado.app.ui.book.tagmanage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.entities.ExcludedTag
import io.legado.app.data.entities.TagMapping
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.theme.adaptiveHorizontalPaddingTab
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.GlassCard
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.dialog.ColorPickerSheet
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.menuItem.MenuItemIcon
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.tabRow.AppTabRow
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
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    var showSearchSheet by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    var tagEdit by remember { mutableStateOf<TagEditData?>(null) }
    var showColorPicker by remember { mutableStateOf(false) }
    var groupEdit by remember { mutableStateOf<BookTagGroup?>(null) }
    var excludedEdit by remember { mutableStateOf<ExcludedTag?>(null) }
    var mappingEdit by remember { mutableStateOf<TagMapping?>(null) }

    val tabs = listOf("标签", "分组", "排除", "映射")

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "标签管理",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBack)
                },
                actions = {
                    TopBarActionButton(
                        onClick = { showSearchSheet = true },
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
                            text = "新增分组",
                            leadingIcon = { MenuItemIcon(Icons.Filled.Folder) },
                            onClick = { dismiss(); groupEdit = BookTagGroup() },
                        )
                        RoundDropdownMenuItem(
                            text = "新增排除项",
                            leadingIcon = { MenuItemIcon(Icons.Filled.Block) },
                            onClick = { dismiss(); excludedEdit = ExcludedTag() },
                        )
                        RoundDropdownMenuItem(
                            text = "新增映射",
                            leadingIcon = { MenuItemIcon(Icons.Filled.SwapHoriz) },
                            onClick = { dismiss(); mappingEdit = TagMapping() },
                        )
                    }
                },
                bottomContent = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .adaptiveHorizontalPaddingTab(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppTabRow(
                            tabTitles = tabs,
                            selectedTabIndex = state.selectedTab,
                            onTabSelected = { index -> onIntent(TagManagementIntent.SelectTab(index)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when (state.selectedTab) {
            0 -> TagListTab(state, onIntent, padding)
            1 -> GroupListTab(state, padding) { groupEdit = it }
            2 -> ExcludedListTab(state, padding) { excludedEdit = it }
            3 -> MappingListTab(state, padding) { mappingEdit = it }
        }
    }

    AppModalBottomSheet(
        show = showSearchSheet,
        onDismissRequest = {
            showSearchSheet = false
            onIntent(TagManagementIntent.Search(""))
        },
        modifier = Modifier.navigationBarsPadding(),
    ) {
        SearchBar(
            query = state.searchQuery,
            onQueryChange = { onIntent(TagManagementIntent.Search(it)) },
            onSearch = {
                onIntent(TagManagementIntent.Search(it))
                showSearchSheet = false
            },
            placeholder = "搜索标签",
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp, 24.dp),
        )
    }

    TagEditSheet(
        data = tagEdit,
        groups = state.groups,
        onValueChange = { tagEdit = it },
        onSave = {
            onIntent(TagManagementIntent.SaveTag(it.id, it.name, it.groupId, it.color))
            tagEdit = null
        },
        onDelete = {
            onIntent(
                TagManagementIntent.DeleteTag(
                    BookTag(id = it.id, name = it.name, groupId = it.groupId, color = it.color),
                ),
            )
            tagEdit = null
        },
        onDismiss = { tagEdit = null },
        onChooseColor = { showColorPicker = true },
    )

    ColorPickerSheet(
        show = showColorPicker,
        initialColor = (tagEdit?.color ?: 0xFF6750A4).toInt(),
        onDismissRequest = { showColorPicker = false },
        onColorSelected = { argb ->
            tagEdit = tagEdit?.copy(color = argb.toLong())
            showColorPicker = false
        },
    )

    GroupEditSheet(
        data = groupEdit,
        onSave = { onIntent(TagManagementIntent.SaveGroup(it.id, it.name)); groupEdit = null },
        onDelete = { onIntent(TagManagementIntent.DeleteGroup(it)); groupEdit = null },
        onDismiss = { groupEdit = null },
    )

    ExcludedEditSheet(
        data = excludedEdit,
        onSave = { onIntent(TagManagementIntent.SaveExcluded(it.id, it.name, it.isRegex)); excludedEdit = null },
        onDelete = { onIntent(TagManagementIntent.DeleteExcluded(it)); excludedEdit = null },
        onDismiss = { excludedEdit = null },
    )

    MappingEditSheet(
        data = mappingEdit,
        tags = state.tags,
        onSave = { onIntent(TagManagementIntent.SaveMapping(it.id, it.oldTagName, it.newTagId)); mappingEdit = null },
        onDelete = { onIntent(TagManagementIntent.DeleteMapping(it)); mappingEdit = null },
        onDismiss = { mappingEdit = null },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagListTab(
    state: TagManagementUiState,
    onIntent: (TagManagementIntent) -> Unit,
    padding: androidx.compose.foundation.layout.PaddingValues,
) {
    val query = state.searchQuery
    val filtered = if (query.isBlank()) state.tags else state.tags.filter { it.name.contains(query, true) }
    val groupIds = state.groups.map { it.id }.toSet()
    val ungrouped = filtered.filter { it.groupId !in groupIds }
    val sections = state.groups.mapNotNull { g ->
        val ts = filtered.filter { it.groupId == g.id }
        if (ts.isEmpty()) null else g.name to ts
    } + ("未分组" to ungrouped)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .adaptiveHorizontalPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = adaptiveContentPadding()),
    ) {
        sections.forEach { (title, tags) ->
            item { Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    tags.forEach { tag ->
                        TagChip(tag = tag, count = state.tagCounts[tag.id] ?: 0) {
                            onIntent(TagManagementIntent.OpenTagDetail(tag.id))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChip(tag: BookTag, count: Int, onClick: () -> Unit) {
    val color = if (tag.color == 0L) LegadoTheme.colorScheme.primary else Color(tag.color)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("${tag.name} ($count)", color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun GroupListTab(
    state: TagManagementUiState,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onEdit: (BookTagGroup) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .adaptiveHorizontalPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = adaptiveContentPadding()),
    ) {
        items(state.groups) { group ->
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                onClick = { onEdit(group) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "标签数：${state.groupTagCounts[group.id] ?: 0}",
                            style = MaterialTheme.typography.bodySmall,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExcludedListTab(
    state: TagManagementUiState,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onEdit: (ExcludedTag) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .adaptiveHorizontalPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = adaptiveContentPadding()),
    ) {
        items(state.excludedTags) { excluded ->
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                onClick = { onEdit(excluded) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(excluded.name, style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(
                        if (excluded.isRegex) "正则" else "关键字",
                        style = MaterialTheme.typography.labelMedium,
                        color = LegadoTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(LegadoTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MappingListTab(
    state: TagManagementUiState,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onEdit: (TagMapping) -> Unit,
) {
    val tagNameById = state.tags.associate { it.id to it.name }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .adaptiveHorizontalPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = adaptiveContentPadding()),
    ) {
        items(state.mappings) { mapping ->
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                onClick = { onEdit(mapping) },
            ) {
                Text(
                    "${mapping.oldTagName}  →  ${tagNameById[mapping.newTagId] ?: "（未找到）"}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
        }
    }
}
