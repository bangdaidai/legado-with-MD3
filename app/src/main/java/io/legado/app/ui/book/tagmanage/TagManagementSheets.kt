package io.legado.app.ui.book.tagmanage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.entities.ExcludedTag
import io.legado.app.data.entities.TagMapping
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.R
import io.legado.app.ui.widget.components.settingItem.SettingItem
import androidx.compose.foundation.layout.weight

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
    onPickColor: () -> Unit,
    onExclude: (String) -> Unit = {},
    aliases: List<TagMapping> = emptyList(),
    onMapToStandard: (String) -> Unit = {},
    onRemoveAlias: (TagMapping) -> Unit = {},
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    // Miuix WindowBottomSheet 会在首次 show 时快照 endAction/startAction，
    // 若直接闭包外部 data 取值，保存/删除会拿到打开弹窗时的旧值（分组、颜色修改丢失）。
    // 用本地可变状态始终镜像最新 data，闭包读状态当前值，规避快照问题。
    var latest by remember { mutableStateOf(data) }
    latest = data
    val current = latest
    val localOnDelete = onDelete
    AppModalBottomSheet(
        show = data != null,
        onDismissRequest = onDismiss,
        title = if (current?.id == 0L) "新增标签" else "编辑标签",
        startAction = if (current != null && current.id != 0L) {
            val c = current
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SmallPlainButton(
                        text = "排除",
                        icon = Icons.Default.Block,
                        onClick = { onExclude(c.name.trim()) },
                    )
                    localOnDelete?.let { del ->
                        SmallPlainButton(
                            text = "删除",
                            icon = Icons.Default.Delete,
                            onClick = del,
                        )
                    }
                }
            }
        } else {
            null
        },
        endAction = {
            SmallPlainButton(text = "保存", onClick = { current?.let { onConfirm(it) } })
        },
    ) {
        if (current != null) {
            val d = current
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 第一行：颜色色块（无文字）+ 标签名
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(d.color))
                            .clickable { onPickColor() },
                    )
                    OutlinedTextField(
                        value = d.name,
                        onValueChange = { onChange(d.copy(name = it)) },
                        label = { Text("标签名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 第二行：分组（带边框的下拉框）
                GroupField(
                    groups = groups,
                    selectedId = d.groupId,
                    onSelect = { onChange(d.copy(groupId = it)) },
                )
                // 别名映射（标签详情页使用）
                if (aliases.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "别名（合并到标准标签）",
                            style = MaterialTheme.typography.titleSmall,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                        )
                        aliases.forEach { mapping ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    mapping.oldTagName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                SmallPlainButton(
                                    text = "设为标准",
                                    onClick = { onMapToStandard(mapping.oldTagName) },
                                )
                                SmallPlainButton(
                                    text = "移除",
                                    icon = Icons.Default.Delete,
                                    onClick = { onRemoveAlias(mapping) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 分组选择：带边框的框，点框弹出分组列表，与标签名输入框风格一致。
 */
@Composable
private fun GroupField(
    groups: List<BookTagGroup>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = groups.find { it.id == selectedId }?.name ?: "未分组"
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selectedName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        }
        RoundDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            RoundDropdownMenuItem(
                text = "未分组",
                isSelected = selectedId == 0L,
                onClick = { onSelect(0L); expanded = false },
            )
            groups.forEach { group ->
                RoundDropdownMenuItem(
                    text = group.name,
                    isSelected = selectedId == group.id,
                    onClick = { onSelect(group.id); expanded = false },
                )
            }
        }
    }
}

/* ---------------- 分组管理（溢出菜单 -> 弹窗） ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupManageSheet(
    show: Boolean,
    groups: List<BookTagGroup>,
    tagCounts: Map<Long, Int>,
    onDismissRequest: () -> Unit,
    onAdd: () -> Unit,
    onUpdateGroup: (BookTagGroup, String) -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(groups, key = { it.id }) { group ->
                TagGroupItem(
                    group = group,
                    tagCount = tagCounts[group.id] ?: 0,
                    onUpdateGroup = onUpdateGroup,
                    onDeleteGroup = onDelete,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagGroupItem(
    group: BookTagGroup,
    tagCount: Int,
    onUpdateGroup: (BookTagGroup, String) -> Unit,
    onDeleteGroup: (BookTagGroup) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val state = rememberTextFieldState(initialText = group.name)

    LaunchedEffect(expanded) {
        if (expanded) {
            state.edit {
                replace(0, length, group.name)
            }
        }
    }

    SettingItem(
        title = group.name,
        description = "标签数：$tagCount",
        expanded = expanded,
        cornerRadius = 12.dp,
        color = MaterialTheme.colorScheme.surface,
        onExpandChange = { expanded = it },
        trailingContent = {
            Row {
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(id = R.string.edit)
                    )
                }
                IconButton(onClick = { onDeleteGroup(group) }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(id = R.string.delete)
                    )
                }
            }
        },
        expandContent = {
            AppTextField(
                state = state,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                label = stringResource(R.string.group_name),
                contentPadding = PaddingValues(
                    top = 4.dp,
                    bottom = 4.dp,
                    start = 12.dp,
                    end = 12.dp
                ),
                onKeyboardAction = {
                    onUpdateGroup(group, state.text.toString())
                    expanded = false
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                SmallPlainButton(
                    onClick = {
                        onUpdateGroup(group, state.text.toString())
                        expanded = false
                    },
                    icon = Icons.Default.Check,
                    text = stringResource(id = R.string.ok)
                )
            }
        }
    )
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
    onDelete: (TagMapping) -> Unit,
) {
    val tagNameById = tags.associate { it.id to it.name }
    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = "标签映射",
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
                    IconButton(onClick = { onDelete(mapping) }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除")
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
