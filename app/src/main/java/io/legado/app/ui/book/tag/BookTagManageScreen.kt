package io.legado.app.ui.book.tag

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.AutoMirrored.Filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.repository.BookTagRepository
import io.legado.app.data.repository.ExcludedTagRepository
import io.legado.app.ui.theme.AppTheme
import io.legado.app.ui.theme.LegadoTheme
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.tooling.preview.Preview
import io.legado.app.utils.TagColorUtils
import kotlinx.coroutines.flow.flow

private fun tagColorInt(tag: BookTag): Int =
    if (tag.color != 0) tag.color else TagColorUtils.generateRandomColor(tag.name)

@Composable
private fun TagBookCount(tagId: Long) {
    if (LocalInspectionMode.current) {
        Text(
            text = "0 本",
            style = LegadoTheme.typography.bodySmall,
            color = LegadoTheme.colorScheme.outline
        )
        return
    }
    val count by flow { emit(BookTagRepository.countBooks(tagId)) }
        .collectAsState(initial = 0)
    Text(
        text = "$count 本",
        style = LegadoTheme.typography.bodySmall,
        color = LegadoTheme.colorScheme.outline
    )
}

@Composable
private fun TagChip(tag: BookTag, onClick: () -> Unit) {
    val color = Color(tagColorInt(tag))
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.16f),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = tag.name,
                style = LegadoTheme.typography.labelLarge,
                color = LegadoTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(4.dp))
            TagBookCount(tag.id)
        }
    }
}

@Composable
private fun TagFlowRow(tags: List<BookTag>, onClick: (BookTag) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tags.forEach { tag ->
            TagChip(tag = tag, onClick = { onClick(tag) })
        }
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text = text,
        style = LegadoTheme.typography.titleSmall,
        color = LegadoTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun ExcludedTagEntry(onClick: () -> Unit) {
    val excludedCount = if (LocalInspectionMode.current) {
        0
    } else {
        ExcludedTagRepository.observeAll()
            .collectAsState(initial = emptyList()).value.size
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = LegadoTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Label,
                contentDescription = null,
                tint = LegadoTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.excluded_tag_manage),
                style = LegadoTheme.typography.bodyLarge,
                color = LegadoTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$excludedCount 个",
                style = LegadoTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.outline
            )
        }
    }
}

/**
 * 标签库管理主界面：分组筛选 + 按组聚合的标签流(每个标签带计数) + 新建/编辑/删除。
 * 使用 Scaffold + TopAppBar 遵循 MD3 规范，并修复状态栏遮挡。
 */
@Composable
fun BookTagManageScreen(
    viewModel: BookTagManageViewModel,
    onBack: () -> Unit,
    onManageExcluded: () -> Unit = {},
    onOpenDetail: (BookTag) -> Unit = {},
) {
    val groups by viewModel.groups.collectAsState(initial = emptyList())
    val allTags by viewModel.tags.collectAsState(initial = emptyList())
    val selectedGroupId by viewModel.selectedGroupId.collectAsState()

    var showEdit by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BookTag?>(null) }

    LaunchedEffect(Unit) { viewModel.syncFromBooks() }

    BookTagManageContent(
        groups = groups,
        allTags = allTags,
        selectedGroupId = selectedGroupId,
        onBack = onBack,
        onManageExcluded = onManageExcluded,
        onNewTag = {
            editing = null
            showEdit = true
        },
        onSelectGroup = { viewModel.setSelectedGroup(it) },
        onOpenDetail = onOpenDetail
    )

    if (showEdit) {
        BookTagEditSheet(
            show = true,
            tag = editing,
            groups = groups,
            onDismissRequest = { showEdit = false },
            onSave = {
                viewModel.saveTag(it)
                showEdit = false
            },
            onDelete = {
                viewModel.deleteTag(it)
                showEdit = false
            },
            onCreateGroup = { viewModel.createGroup(it) }
        )
    }
}

/**
 * 纯展示层：不依赖 ViewModel / 数据库，供 [BookTagManageScreen] 与 @Preview 复用。
 */
@Composable
private fun BookTagManageContent(
    groups: List<BookTagGroup>,
    allTags: List<BookTag>,
    selectedGroupId: Long?,
    onBack: () -> Unit,
    onManageExcluded: () -> Unit,
    onNewTag: () -> Unit,
    onOpenDetail: (BookTag) -> Unit,
    onSelectGroup: (Long?) -> Unit,
) {
    // 按组聚合：未分组 + 各分组，依次排列
    val sections = remember(allTags, groups) {
        val ungrouped = allTags.filter { it.groupId == 0L }
        val grouped = groups.mapNotNull { g ->
            val ts = allTags.filter { it.groupId == g.id }
            if (ts.isEmpty()) null else g to ts
        }
        buildList {
            if (ungrouped.isNotEmpty()) add(null to ungrouped)
            addAll(grouped)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.book_tag_manage)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNewTag) {
                Icon(Icons.Default.Add, contentDescription = "新建标签")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedGroupId == null,
                        onClick = { onSelectGroup(null) },
                        label = { Text("全部") }
                    )
                }
                items(groups) { group ->
                    FilterChip(
                        selected = selectedGroupId == group.id,
                        onClick = { onSelectGroup(group.id) },
                        label = { Text(group.name) }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (selectedGroupId == null) {
                    item(key = "excluded") { ExcludedTagEntry(onClick = onManageExcluded) }
                    sections.forEach { (group, tags) ->
                        item(key = "title-${group?.id ?: -1}") {
                            GroupTitle(text = group?.name ?: stringResource(R.string.ungrouped))
                        }
                        item(key = "flow-${group?.id ?: -1}") {
                            TagFlowRow(tags = tags, onClick = onOpenDetail)
                        }
                    }
                } else {
                    item(key = "excluded2") { ExcludedTagEntry(onClick = onManageExcluded) }
                    val tags = allTags.filter { it.groupId == selectedGroupId }
                    item(key = "flow-sel") {
                        TagFlowRow(tags = tags, onClick = onOpenDetail)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "标签管理")
@Composable
private fun BookTagManageScreenPreview() {
    AppTheme(AppUiConfiguration()) {
        BookTagManageContent(
            groups = listOf(
                BookTagGroup(id = 1, name = "题材"),
                BookTagGroup(id = 2, name = "状态"),
                BookTagGroup(id = 3, name = "口味"),
            ),
            allTags = listOf(
                BookTag(id = 1, name = "言情", color = 0xFFFF8A80.toInt(), groupId = 1),
                BookTag(id = 2, name = "悬疑", color = 0xFF82B1FF.toInt(), groupId = 1),
                BookTag(id = 3, name = "科幻", color = 0xFFB388FF.toInt(), groupId = 1),
                BookTag(id = 4, name = "连载中", color = 0xFFFFD180.toInt(), groupId = 2),
                BookTag(id = 5, name = "已完结", color = 0xFFA7FFEB.toInt(), groupId = 2),
                BookTag(id = 6, name = "甜宠", color = 0xFFFF80AB.toInt(), groupId = 3),
                BookTag(id = 7, name = "热血", color = 0xFFFF5252.toInt(), groupId = 0),
                BookTag(id = 8, name = "轻松", color = 0xFF69F0AE.toInt(), groupId = 0),
            ),
            selectedGroupId = null,
            onBack = {},
            onManageExcluded = {},
            onNewTag = {},
            onOpenDetail = {},
            onSelectGroup = {},
        )
    }
}
