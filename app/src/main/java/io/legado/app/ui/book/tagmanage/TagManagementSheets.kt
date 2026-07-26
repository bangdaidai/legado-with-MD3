package io.legado.app.ui.book.tagmanage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet

private val TAG_COLOR_PALETTE = listOf(
    0xFF6750A4, 0xFF625B71, 0xFF7D5260, 0xFFB3261E,
    0xFF835785, 0xFF386A20, 0xFF006A6A, 0xFF00558E,
    0xFF8C5B00, 0xFF4A4458, 0xFF1C1B1F, 0xFF49454F,
    0xFF9C27B0, 0xFF2196F3, 0xFF00897B, 0xFFEF6C00,
).map { it.toLong() }

data class TagEditData(
    val id: Long = 0,
    val name: String = "",
    val groupId: Long = 0,
    val color: Long = 0,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagEditSheet(
    data: TagEditData?,
    groups: List<BookTagGroup>,
    onValueChange: (TagEditData) -> Unit,
    onSave: (TagEditData) -> Unit,
    onDelete: (TagEditData) -> Unit,
    onDismiss: () -> Unit,
    onChooseColor: () -> Unit,
) {
    AppModalBottomSheet(
        data = data,
        onDismissRequest = onDismiss,
        title = if (data?.id == 0L) "新增标签" else "编辑标签",
    ) { d ->
        var name by remember(d) { mutableStateOf(d.name) }
        var groupId by remember(d) { mutableStateOf(d.groupId) }
        var expanded by remember { mutableStateOf(false) }
        val groupName = groups.firstOrNull { it.id == groupId }?.name ?: "未分组"

        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("标签名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(modifier = Modifier.padding(top = 12.dp)) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("分组") },
                    modifier = Modifier.fillMaxWidth().clickable { expanded = true },
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text("未分组") },
                        onClick = { groupId = 0; expanded = false },
                    )
                    groups.forEach { g ->
                        DropdownMenuItem(
                            text = { Text(g.name) },
                            onClick = { groupId = g.id; expanded = false },
                        )
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(d.color))
                        .border(1.dp, LegadoTheme.colorScheme.outlineVariant, CircleShape),
                )
                MediumTonalButton(
                    onClick = onChooseColor,
                    modifier = Modifier.padding(start = 12.dp),
                    text = "选择颜色",
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                TAG_COLOR_PALETTE.forEach { c ->
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(1.dp, LegadoTheme.colorScheme.outlineVariant, CircleShape)
                            .clickable { onValueChange(d.copy(color = c)) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (d.id != 0L) {
                    MediumTonalButton(onClick = { onDelete(d) }, text = "删除")
                }
                MediumTonalButton(
                    onClick = { onSave(d.copy(name = name, groupId = groupId, color = d.color)) },
                    modifier = Modifier.padding(start = 12.dp),
                    text = "保存",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupEditSheet(
    data: BookTagGroup?,
    onSave: (BookTagGroup) -> Unit,
    onDelete: (BookTagGroup) -> Unit,
    onDismiss: () -> Unit,
) {
    AppModalBottomSheet(
        data = data,
        onDismissRequest = onDismiss,
        title = if (data?.id == 0L) "新增分组" else "编辑分组",
    ) { d ->
        var name by remember(d) { mutableStateOf(d.name) }
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("分组名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (d.id != 0L) {
                    MediumTonalButton(onClick = { onDelete(d) }, text = "删除")
                }
                MediumTonalButton(
                    onClick = { onSave(d.copy(name = name)) },
                    modifier = Modifier.padding(start = 12.dp),
                    text = "保存",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExcludedEditSheet(
    data: ExcludedTag?,
    onSave: (ExcludedTag) -> Unit,
    onDelete: (ExcludedTag) -> Unit,
    onDismiss: () -> Unit,
) {
    AppModalBottomSheet(
        data = data,
        onDismissRequest = onDismiss,
        title = if (data?.id == 0L) "新增排除项" else "编辑排除项",
    ) { d ->
        var name by remember(d) { mutableStateOf(d.name) }
        var isRegex by remember(d) { mutableStateOf(d.isRegex) }
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(if (isRegex) "正则表达式" else "关键字") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Text("按正则匹配", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = isRegex, onCheckedChange = { isRegex = it })
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (d.id != 0L) {
                    MediumTonalButton(onClick = { onDelete(d) }, text = "删除")
                }
                MediumTonalButton(
                    onClick = { onSave(d.copy(name = name, isRegex = isRegex)) },
                    modifier = Modifier.padding(start = 12.dp),
                    text = "保存",
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MappingEditSheet(
    data: TagMapping?,
    tags: List<BookTag>,
    onSave: (TagMapping) -> Unit,
    onDelete: (TagMapping) -> Unit,
    onDismiss: () -> Unit,
) {
    AppModalBottomSheet(
        data = data,
        onDismissRequest = onDismiss,
        title = if (data?.id == 0L) "新增映射" else "编辑映射",
    ) { d ->
        var oldTagName by remember(d) { mutableStateOf(d.oldTagName) }
        var newTagId by remember(d) { mutableStateOf(d.newTagId) }
        var expanded by remember { mutableStateOf(false) }
        val targetName = tags.firstOrNull { it.id == newTagId }?.name ?: "选择标准标签"

        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            OutlinedTextField(
                value = oldTagName,
                onValueChange = { oldTagName = it },
                label = { Text("异名（如：玄幻小说）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(modifier = Modifier.padding(top = 12.dp)) {
                OutlinedTextField(
                    value = targetName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("映射到标准标签") },
                    modifier = Modifier.fillMaxWidth().clickable { expanded = true },
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    tags.forEach { t ->
                        DropdownMenuItem(
                            text = { Text(t.name) },
                            onClick = { newTagId = t.id; expanded = false },
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                if (d.id != 0L) {
                    MediumTonalButton(onClick = { onDelete(d) }, text = "删除")
                }
                MediumTonalButton(
                    onClick = { onSave(d.copy(oldTagName = oldTagName, newTagId = newTagId)) },
                    modifier = Modifier.padding(start = 12.dp),
                    text = "保存",
                )
            }
        }
    }
}
