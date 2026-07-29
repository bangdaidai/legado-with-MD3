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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
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
import io.legado.app.ui.widget.components.card.SelectionItemCard

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
    onExclude: ((String) -> Unit)? = null,
    aliases: List<TagMapping> = emptyList(),
    onMapToStandard: (String) -> Unit = {},
    onRemoveAlias: (TagMapping) -> Unit = {},
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AppModalBottomSheet(
        show = data != null,
        onDismissRequest = onDismiss,
        title = if (data?.id == 0L) "新增标签" else "编辑标签",
    ) {
        data?.let { d ->
            val selectedColor = Color(d.color)
            val themePresetColors = remember {
                val cs = LegadoTheme.colorScheme
                listOf(
                    cs.primary,
                    cs.secondary,
                    cs.tertiary,
                    cs.error,
                    cs.primaryContainer,
                    cs.secondaryContainer,
                    cs.tertiaryContainer,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    item { CustomColorChip(onClick = onPickColor) }
                    items(themePresetColors) { preset ->
                        ColorChip(
                            color = preset,
                            selected = d.color == preset.value.toLong(),
                            onClick = { onChange(d.copy(color = preset.value.toLong())) },
                        )
                    }
                }

                // 标签名称
                OutlinedTextField(
                    value = d.name,
                    onValueChange = { onChange(d.copy(name = it)) },
                    label = { Text("标签名") },
                    placeholder = { Text("例如：工作计划") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = selectedColor,
                        focusedLabelColor = selectedColor,
                    ),
                )

                // 分组
                GroupField(
                    groups = groups,
                    selectedId = d.groupId,
                    onSelect = { onChange(d.copy(groupId = it)) },
                )

                // 别名
                if (aliases.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        aliases.forEach { mapping ->
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(LegadoTheme.colorScheme.surfaceVariant)
                                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    mapping.oldTagName,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(LegadoTheme.colorScheme.surface)
                                        .clickable { onRemoveAlias(mapping) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "移除",
                                        modifier = Modifier.size(12.dp),
                                        tint = LegadoTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                // 底部操作栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    onExclude?.let { ex ->
                        OutlinedButton(
                            onClick = { d.name.trim().let(ex) },
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.height(44.dp),
                        ) { Text("排除") }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { onConfirm(d) },
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.height(44.dp),
                    ) { Text("保存") }
                }
            }
        }
    }
}

/**
 * 分组选择：标准纵向下拉菜单（ExposedDropdownMenuBox + AppTextField）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupField(
    groups: List<BookTagGroup>,
    selectedId: Long,
    onSelect: (Long) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = groups.find { it.id == selectedId }?.name ?: "未分组"
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        AppTextField(
            value = selectedName,
            onValueChange = {},
            readOnly = true,
            label = "分组",
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
        )
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
    ) {
        if (data != null) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分组名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (data.id != 0L) {
                        SmallPlainButton(
                            text = "删除",
                            icon = Icons.Default.Delete,
                            onClick = { onDelete(data) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    SmallPlainButton(text = "保存", onClick = {
                        data?.let { d -> onSave(d.copy(name = name.trim())) }
                    })
                }
            }
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
        if (mappings.isEmpty()) {
            Text(
                "暂无标签映射",
                style = MaterialTheme.typography.bodyMedium,
                color = LegadoTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 32.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(mappings, key = { it.id }) { mapping ->
                    val standardName = tagNameById[mapping.newTagId] ?: "（未找到）"
                    SelectionItemCard(
                        title = mapping.oldTagName,
                        subtitle = "映射到：$standardName",
                        containerColor = LegadoTheme.colorScheme.onSheetContent,
                        trailingAction = {
                            SmallPlainButton(
                                onClick = { onDelete(mapping) },
                                icon = Icons.Default.Delete,
                                contentDescription = "删除",
                            )
                        },
                    )
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
    ) {
        if (data != null) {
            val cs = LegadoTheme.colorScheme
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称/正则") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = cs.primary,
                        focusedLabelColor = cs.primary,
                    ),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isRegex,
                        onCheckedChange = { isRegex = it },
                    )
                    Text(
                        "作为正则匹配",
                        style = MaterialTheme.typography.bodyMedium,
                        color = cs.onSurface,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (data.id != 0L) {
                        OutlinedButton(
                            onClick = { onDelete(data) },
                            shape = RoundedCornerShape(22.dp),
                            modifier = Modifier.height(44.dp),
                        ) {
                            Text("删除")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = {
                            data?.let { d ->
                                onSave(d.copy(name = name.trim(), isRegex = isRegex))
                            }
                        },
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier.height(44.dp),
                    ) {
                        Text("保存")
                    }
                }
            }
        }
    }
}

/* ---------------- 颜色辅助组件 ---------------- */

@Composable
private fun ColorChip(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(if (selected) color.copy(alpha = 0.15f) else Color.Transparent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun CustomColorChip(onClick: () -> Unit) {
    val cs = LegadoTheme.colorScheme
    val rainbowBrush = Brush.sweepGradient(
        listOf(cs.primary, cs.secondary, cs.tertiary, cs.error, cs.primary),
    )
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .border(1.5.dp, cs.outlineVariant, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(rainbowBrush),
            contentAlignment = Alignment.Center,
        ) {
            Box(modifier = Modifier.size(14.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.Center)
                        .background(Color.White),
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(2.dp)
                        .align(Alignment.Center)
                        .background(Color.White),
                )
            }
        }
    }
}
