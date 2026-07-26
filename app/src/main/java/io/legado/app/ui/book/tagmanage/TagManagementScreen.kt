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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.entities.ExcludedTag
import io.legado.app.data.entities.TagMapping
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.dialog.ColorPickerSheet
import io.legado.app.ui.widget.components.tabRow.AppTabRow
import kotlinx.collections.immutable.ImmutableList

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagManagementScreen(
    state: TagManagementUiState,
    onIntent: (TagManagementIntent) -> Unit,
    onBack: () -> Unit,
) {
    var showSearch by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    var tagEdit by remember { mutableStateOf<TagEditData?>(null) }
    var showColorPicker by remember { mutableStateOf(false) }
    var groupEdit by remember { mutableStateOf<BookTagGroup?>(null) }
    var excludedEdit by remember { mutableStateOf<ExcludedTag?>(null) }
    var mappingEdit by remember { mutableStateOf<TagMapping?>(null) }

    val tabs = listOf("标签", "分组", "排除", "映射")

    AppScaffold(
        topBar = { hazeState ->
            TopAppBar(
                title = {
                    if (showSearch) {
                        OutlinedTextField(
                            value = state.searchQuery,
                            onValueChange = { onIntent(TagManagementIntent.Search(it)) },
                            placeholder = { Text("搜索标签") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("标签管理")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (showSearch) {
                        IconButton(onClick = {
                            showSearch = false
                            onIntent(TagManagementIntent.Search(""))
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭搜索")
                        }
                    } else {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "搜索")
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("新增标签") },
                                    onClick = {
                                        menuExpanded = false
                                        tagEdit = TagEditData()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("新增分组") },
                                    onClick = {
                                        menuExpanded = false
                                        groupEdit = BookTagGroup()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("新增排除项") },
                                    onClick = {
                                        menuExpanded = false
                                        excludedEdit = ExcludedTag()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("新增映射") },
                                    onClick = {
                                        menuExpanded = false
                                        mappingEdit = TagMapping()
                                    },
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = LegadoTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AppTabRow(
                tabTitles = tabs,
                selectedTabIndex = state.selectedTab,
                onTabSelected = { index -> onIntent(TagManagementIntent.SelectTab(index)) },
            )
            when (state.selectedTab) {
                0 -> TagListTab(state, onIntent)
                1 -> GroupListTab(state) { groupEdit = it }
                2 -> ExcludedListTab(state) { excludedEdit = it }
                3 -> MappingListTab(state) { mappingEdit = it }
            }
        }
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
) {
    val query = state.searchQuery
    val filtered = if (query.isBlank()) state.tags else state.tags.filter { it.name.contains(query, true) }
    val groupIds = state.groups.map { it.id }.toSet()
    val ungrouped = filtered.filter { it.groupId !in groupIds }
    val sections = state.groups.mapNotNull { g ->
        val ts = filtered.filter { it.groupId == g.id }
        if (ts.isEmpty()) null else g.name to ts
    } + ("未分组" to ungrouped)

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
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
private fun GroupListTab(state: TagManagementUiState, onEdit: (BookTagGroup) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(state.groups) { group ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(group) }
                    .padding(vertical = 12.dp),
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

@Composable
private fun ExcludedListTab(state: TagManagementUiState, onEdit: (ExcludedTag) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(state.excludedTags) { excluded ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(excluded) }
                    .padding(vertical = 12.dp),
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

@Composable
private fun MappingListTab(state: TagManagementUiState, onEdit: (TagMapping) -> Unit) {
    val tagNameById = state.tags.associate { it.id to it.name }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(state.mappings) { mapping ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(mapping) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${mapping.oldTagName}  →  ${tagNameById[mapping.newTagId] ?: "（未找到）"}",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}
