package io.legado.app.ui.book.tagmanage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuLazy
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet

/* ---------------- 标签编辑（受控组件） ---------------- */

data class TagEditData(
    val id: Long = 0L,
    val name: String = "",
    val groupId: Long = 0L,
    val color: Long = 0xFF6200EE,
)

@Composable
fun TagEditSheet(
    data: TagEditData?,
    groups: List<BookTagGroup>,
    onChange: (TagEditData) -> Unit,
    onConfirm: (TagEditData) -> Unit,
    onDelete: ((TagEditData) -> Unit)? = null,
    onPickColor: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppModalBottomSheet(
        show = data != null,
        onDismissRequest = onDismiss,
        title = if (data?.id == 0L) "新增标签" else "编辑标签",
        startAction = if (data != null && data.id != 0L && onDelete != null) {
            { SmallPlainButton(text = "删除", icon = Icons.Default.Delete, onClick = { onDelete(data) }) }
        } else {
            null
        },
        endAction = {
            SmallPlainButton(text = "保存", onClick = { data?.let { onConfirm(it) } })
        },
    ) {
        if (data != null) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = data.name,
                    onValueChange = { onChange(data.copy(name = it)) },
                    label = { Text("标签名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("分组", style = MaterialTheme.typography.labelMedium, color = LegadoTheme.colorScheme.onSurfaceVariant)
                GroupDropdown(groups = groups, selectedId = data.groupId) {
                    onChange(data.copy(groupId = it))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("颜色", style = MaterialTheme.typography.labelMedium, color = LegadoTheme.colorScheme.onSurfaceVariant)
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(data.color))
                            .clickable { onPickColor() },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupDropdown(
    groups: List<BookTagGroup>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = groups.find { it.id == selectedId }?.name ?: "未分组"
    Box {
        SmallPlainButton(text = selectedName, onClick = { expanded = true })
        RoundDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            RoundDropdownMenuItem(
                text = "未分组",
                isSelected = selectedId == 0L,
                onClick = { onSelect(0L); expanded = false }
            )
            groups.forEach { group ->
                RoundDropdownMenuItem(
                    text = group.name,
                    isSelected = selectedId == group.id,
                    onClick = { onSelect(group.id); expanded = false }
                )
            }
        }
    }
}

/* ---------------- 分组管理（溢出菜单 -> 弹窗） ---------------- */

@Composable
fun GroupManageSheet(
    show: Boolean,
    groups: List<BookTagGroup>,
    tagCounts: Map<Long, Int>,
    onDismissRequest: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (BookTagGroup) -> Unit,
    onDelete: (BookTagGroup) -> Unit,
) {
    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = "分组管理",
        endAction = { SmallPlainButton(text = "新增", icon = Icons.Default.Add, onClick = onAdd) },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(groups, key = { it.id }) { group ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "标签数：${tagCounts[group.id] ?: 0}",
                            style = MaterialTheme.typography.bodySmall,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { onEdit(group) }) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = { onDelete(group) }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                }
            }
        }
    }
}

@Composable
fun GroupEditSheet(
    data: BookTagGroup?,
    onSave: (BookTagGroup) -> Unit,
    onDelete: (BookTagGroup) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(data) { mutableStateOf(data?.name ?: "") }
    AppModalBottomSheet(
        show = data != null,
        onDismissRequest = onDismiss,
        title = if (data?.id == 0L) "新增分组" else "编辑分组",
        startAction = if (data != null && data.id != 0L) {
            { SmallPlainButton(text = "删除", icon = Icons.Default.Delete, onClick = { onDelete(data) }) }
        } else {
            null
        },
        endAction = {
            SmallPlainButton(text = "保存", onClick = {
                data?.let { d -> onSave(d.copy(name = name.trim())) }
            })
        },
    ) {
        if (data != null) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("分组名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/* ---------------- 标签映射管理（溢出菜单 -> 弹窗） ---------------- */

@Composable
fun MappingManageSheet(
    show: Boolean,
    mappings: List<TagMapping>,
    tags: List<BookTag>,
    onDismissRequest: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (TagMapping) -> Unit,
    onDelete: (TagMapping) -> Unit,
) {
    val tagNameById = tags.associate { it.id to it.name }
    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = "标签映射",
        endAction = { SmallPlainButton(text = "新增", icon = Icons.Default.Add, onClick = onAdd) },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(mappings, key = { it.id }) { mapping ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${mapping.oldTagName}  →  ${tagNameById[mapping.newTagId] ?: "（未找到）"}",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onEdit(mapping) }) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                    IconButton(onClick = { onDelete(mapping) }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
                    }
                }
            }
        }
    }
}

@Composable
fun MappingEditSheet(
    data: TagMapping?,
    tags: List<BookTag>,
    onSave: (TagMapping) -> Unit,
    onDelete: (TagMapping) -> Unit,
    onDismiss: () -> Unit,
) {
    var oldName by remember(data) { mutableStateOf(data?.oldTagName ?: "") }
    var selectedTagId by remember(data) { mutableStateOf(data?.newTagId ?: 0L) }
    AppModalBottomSheet(
        show = data != null,
        onDismissRequest = onDismiss,
        title = if (data?.id == 0L) "新增映射" else "编辑映射",
        startAction = if (data != null && data.id != 0L) {
            { SmallPlainButton(text = "删除", icon = Icons.Default.Delete, onClick = { onDelete(data) }) }
        } else {
            null
        },
        endAction = {
            SmallPlainButton(text = "保存", onClick = {
                data?.let { d -> onSave(d.copy(oldTagName = oldName.trim(), newTagId = selectedTagId)) }
            })
        },
    ) {
        if (data != null) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = oldName,
                    onValueChange = { oldName = it },
                    label = { Text("异名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("标准标签", style = MaterialTheme.typography.labelMedium, color = LegadoTheme.colorScheme.onSurfaceVariant)
                var expanded by remember { mutableStateOf(false) }
                val selectedName = tags.find { it.id == selectedTagId }?.name ?: "请选择"
                Box {
                    SmallPlainButton(text = selectedName, onClick = { expanded = true })
                    RoundDropdownMenuLazy(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        tags.forEach { tag ->
                            item {
                                RoundDropdownMenuItem(
                                    text = tag.name,
                                    isSelected = selectedTagId == tag.id,
                                    onClick = {
                                        selectedTagId = tag.id
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- 排除标签编辑（独立页面使用） ---------------- */

@Composable
fun ExcludedEditSheet(
    data: ExcludedTag?,
    onSave: (ExcludedTag) -> Unit,
    onDelete: (ExcludedTag) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(data) { mutableStateOf(data?.name ?: "") }
    var isRegex by remember(data) { mutableStateOf(data?.isRegex ?: false) }
    AppModalBottomSheet(
        show = data != null,
        onDismissRequest = onDismiss,
        title = if (data?.id == 0L) "新增排除项" else "编辑排除项",
        startAction = if (data != null && data.id != 0L) {
            { SmallPlainButton(text = "删除", icon = Icons.Default.Delete, onClick = { onDelete(data) }) }
        } else {
            null
        },
        endAction = {
            SmallPlainButton(text = "保存", onClick = {
                data?.let { d -> onSave(d.copy(name = name.trim(), isRegex = isRegex)) }
            })
        },
    ) {
        if (data != null) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称/正则") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isRegex, onCheckedChange = { isRegex = it })
                    Text("作为正则匹配", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
