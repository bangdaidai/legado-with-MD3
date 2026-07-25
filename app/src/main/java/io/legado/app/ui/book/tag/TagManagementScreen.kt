package io.legado.app.ui.book.tag

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.entities.ExcludedTag
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.button.series.MediumPlainButton
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.icon.AppIcon
import io.legado.app.ui.widget.components.button.AppIconButton
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.TagColorUtils
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private enum class TagManagementTab(val label: String) {
    TAGS("标签"),
    GROUPS("分组"),
    EXCLUDED("排除标签"),
}

@Composable
fun TagManagementRoute(onBack: () -> Unit) {
    val viewModel: TagManagementViewModel = koinViewModel()
    val tags by viewModel.tags.collectAsState(initial = emptyList())
    val groups by viewModel.groups.collectAsState(initial = emptyList())
    val excluded by viewModel.excludedTags.collectAsState(initial = emptyList())
    TagManagementScreen(
        tags = tags,
        groups = groups,
        excluded = excluded,
        onBack = onBack,
        onSaveTag = viewModel::saveTag,
        onDeleteTag = viewModel::deleteTag,
        onCreateGroup = viewModel::createGroup,
        onDeleteGroup = viewModel::deleteGroup,
        onAddExcluded = viewModel::addExcluded,
        onRemoveExcluded = viewModel::removeExcluded,
    )
}

@Composable
private fun TagManagementScreen(
    tags: List<BookTag>,
    groups: List<BookTagGroup>,
    excluded: List<ExcludedTag>,
    onBack: () -> Unit,
    onSaveTag: (BookTag) -> Unit,
    onDeleteTag: (BookTag) -> Unit,
    onCreateGroup: suspend (String) -> Long,
    onDeleteGroup: (BookTagGroup) -> Unit,
    onAddExcluded: (String) -> Unit,
    onRemoveExcluded: (String) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(TagManagementTab.TAGS) }
    var showEditSheet by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<BookTag?>(null) }
    var pendingDeleteTag by remember { mutableStateOf<BookTag?>(null) }

    AppScaffold(
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "标签库管理",
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
            )
        },
        floatingActionButton = {
            if (selectedTab == TagManagementTab.TAGS) {
                AppFloatingActionButton(
                    onClick = {
                        editingTag = null
                        showEditSheet = true
                    },
                    icon = Icons.Default.Add,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab.ordinal) {
                TagManagementTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { AppText(tab.label) },
                    )
                }
            }
            when (selectedTab) {
                TagManagementTab.TAGS -> TagsTabContent(
                    tags = tags,
                    groups = groups,
                    onEdit = {
                        editingTag = it
                        showEditSheet = true
                    },
                    onRequestDelete = { pendingDeleteTag = it },
                    modifier = Modifier.fillMaxSize().weight(1f),
                )

                TagManagementTab.GROUPS -> GroupsTabContent(
                    groups = groups,
                    tags = tags,
                    onCreate = onCreateGroup,
                    onDelete = onDeleteGroup,
                    modifier = Modifier.fillMaxSize().weight(1f),
                )

                TagManagementTab.EXCLUDED -> ExcludedTabContent(
                    excluded = excluded,
                    onAdd = onAddExcluded,
                    onRemove = onRemoveExcluded,
                    modifier = Modifier.fillMaxSize().weight(1f),
                )
            }
        }

        BookTagEditSheet(
            show = showEditSheet,
            tag = editingTag,
            groups = groups,
            onDismissRequest = { showEditSheet = false },
            onSave = {
                onSaveTag(it)
                showEditSheet = false
            },
            onDelete = {
                pendingDeleteTag = it
                showEditSheet = false
            },
            onCreateGroup = onCreateGroup,
        )

        pendingDeleteTag?.let { tag ->
            AppAlertDialog(
                show = true,
                onDismissRequest = { pendingDeleteTag = null },
                title = "删除标签",
                text = "确定删除标签「${tag.name}」？关联书籍的映射也会一并移除。",
                confirmText = "删除",
                onConfirm = {
                    onDeleteTag(tag)
                    pendingDeleteTag = null
                },
                onDismiss = { pendingDeleteTag = null },
            )
        }
    }
}

@Composable
private fun TagsTabContent(
    tags: List<BookTag>,
    groups: List<BookTagGroup>,
    onEdit: (BookTag) -> Unit,
    onRequestDelete: (BookTag) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tags.isEmpty()) {
        EmptyMessage(message = "还没有标签，点击右下角 + 新建")
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
    ) {
        items(tags, key = { it.id }) { tag ->
            val groupName = groups.firstOrNull { it.id == tag.groupId }?.name ?: "未分组"
            GlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .adaptiveHorizontalPadding(vertical = 4.dp)
                    .clickable { onEdit(tag) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val dotColor = if (tag.color != 0) {
                        Color(tag.color)
                    } else {
                        Color(TagColorUtils.generateRandomColor(tag.name))
                    }
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(dotColor),
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        AppText(tag.name, style = LegadoTheme.typography.titleMedium)
                        AppText(groupName, style = LegadoTheme.typography.bodySmall)
                    }
                    AppIconButton(onClick = { onRequestDelete(tag) }) {
                        AppIcon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = LegadoTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupsTabContent(
    groups: List<BookTagGroup>,
    tags: List<BookTag>,
    onCreate: suspend (String) -> Long,
    onDelete: (BookTagGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newGroupName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .adaptiveHorizontalPadding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTextField(
                value = newGroupName,
                onValueChange = { newGroupName = it },
                label = "新分组名称",
                singleLine = true,
                backgroundColor = LegadoTheme.colorScheme.surface,
                modifier = Modifier.weight(1f),
            )
            AppIconButton(
                onClick = {
                    if (newGroupName.isNotBlank()) {
                        scope.launch {
                            onCreate(newGroupName.trim())
                            newGroupName = ""
                        }
                    }
                },
            ) {
                AppIcon(Icons.Default.Add, contentDescription = null)
            }
        }
        if (groups.isEmpty()) {
            EmptyMessage(message = "还没有分组")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
            ) {
                items(groups, key = { it.id }) { group ->
                    val count = tags.count { it.groupId == group.id }
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .adaptiveHorizontalPadding(vertical = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                AppText(group.name, style = LegadoTheme.typography.titleMedium)
                                AppText(
                                    "$count 个标签",
                                    style = LegadoTheme.typography.bodySmall,
                                )
                            }
                            AppIconButton(onClick = { onDelete(group) }) {
                                AppIcon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = LegadoTheme.colorScheme.error,
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
private fun ExcludedTabContent(
    excluded: List<ExcludedTag>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newName by remember { mutableStateOf("") }
    Column(modifier = modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .adaptiveHorizontalPadding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTextField(
                value = newName,
                onValueChange = { newName = it },
                label = "排除的标签名",
                singleLine = true,
                backgroundColor = LegadoTheme.colorScheme.surface,
                modifier = Modifier.weight(1f),
            )
            AppIconButton(
                onClick = {
                    if (newName.isNotBlank()) {
                        onAdd(newName.trim())
                        newName = ""
                    }
                },
            ) {
                AppIcon(Icons.Default.Add, contentDescription = null)
            }
        }
        if (excluded.isEmpty()) {
            EmptyMessage(message = "还没有排除的标签")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
            ) {
                items(excluded, key = { it.name }) { item ->
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .adaptiveHorizontalPadding(vertical = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppText(
                                item.name,
                                style = LegadoTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            AppIconButton(onClick = { onRemove(item.name) }) {
                                AppIcon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = LegadoTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
