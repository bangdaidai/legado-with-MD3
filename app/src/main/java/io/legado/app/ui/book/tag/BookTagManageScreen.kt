package io.legado.app.ui.book.tag

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundulation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Label
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.repository.BookTagRepository
import io.legado.app.data.repository.ExcludedTagRepository
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.button.series.MediumPlainButton
import io.legado.app.utils.TagColorUtils
import kotlinx.coroutines.flow.flow

private fun tagColorInt(tag: BookTag): Int =
    if (tag.color != 0) tag.color else TagColorUtils.generateRandomColor(tag.name)

@Composable
private fun TagBookCount(tagId: Long) {
    val count by flow { emit(BookTagRepository.countBooks(tagId)) }
        .collectAsState(initial = 0)
    Text(
        text = "$count 本",
        style = LegadoTheme.typography.bodySmall,
        color = LegadoTheme.colorScheme.outline
    )
}

@Composable
private fun GroupFilterChip(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) LegadoTheme.colorScheme.primary else LegadoTheme.colorScheme.surface,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = name,
            color = if (selected) LegadoTheme.colorScheme.onPrimary else LegadoTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun TagManageRow(tag: BookTag, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(Color(tagColorInt(tag)))
        )
        widthSpacer()
        Text(
            text = tag.name,
            style = LegadoTheme.typography.bodyLarge,
            color = LegadoTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        TagBookCount(tag.id)
    }
}

@Composable
private fun widthSpacer() {
    androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
}

@Composable
private fun ExcludedTagEntry(onClick: () -> Unit) {
    val excludedCount by ExcludedTagRepository.observeAll()
        .collectAsState(initial = emptyList())
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Label,
            contentDescription = null,
            tint = LegadoTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        widthSpacer()
        Text(
            text = "排除标签管理",
            style = LegadoTheme.typography.bodyLarge,
            color = LegadoTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${excludedCount.size} 个",
            style = LegadoTheme.typography.bodySmall,
            color = LegadoTheme.colorScheme.outline
        )
    }
}

/**
 * 标签库管理主界面（F1）：分组筛选、标签列表（颜色/计数）、新建/编辑/删除。
 */
@Composable
fun BookTagManageScreen(
    viewModel: BookTagManageViewModel,
    onBack: () -> Unit,
    onManageExcluded: () -> Unit = {},
) {
    val groups by viewModel.groups.collectAsState(initial = emptyList())
    val allTags by viewModel.tags.collectAsState(initial = emptyList())
    val selectedGroupId by viewModel.selectedGroupId.collectAsState()
    val filtered = viewModel.filteredTags(allTags, selectedGroupId)

    var showEdit by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BookTag?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LegadoTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                MediumPlainButton(
                    onClick = onBack,
                    icon = Icons.Default.Close,
                    contentDescription = "返回"
                )
                widthSpacer()
                Text(
                    text = "标签库",
                    style = LegadoTheme.typography.titleLarge,
                    color = LegadoTheme.colorScheme.onSurface
                )
            }
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    GroupFilterChip(
                        name = "全部",
                        selected = selectedGroupId == null,
                        onClick = { viewModel.setSelectedGroup(null) }
                    )
                }
                items(groups) { group ->
                    GroupFilterChip(
                        name = group.name,
                        selected = selectedGroupId == group.id,
                        onClick = { viewModel.setSelectedGroup(group.id) }
                    )
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                item(key = "excluded_entry") {
                    ExcludedTagEntry(onClick = onManageExcluded)
                }
                items(filtered, key = { it.id }) { tag ->
                    TagManageRow(
                        tag = tag,
                        onClick = {
                            editing = tag
                            showEdit = true
                        }
                    )
                }
            }
        }
        AppFloatingActionButton(
            onClick = {
                editing = null
                showEdit = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            icon = Icons.Default.Add
        )
    }
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
