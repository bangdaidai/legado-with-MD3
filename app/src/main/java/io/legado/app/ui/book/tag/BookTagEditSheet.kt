package io.legado.app.ui.book.tag

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.button.series.MediumPlainButton
import io.legado.app.ui.widget.components.dialog.ColorPickerSheet
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import kotlinx.coroutines.launch

/**
 * 标签编辑底部面板：名称、颜色（颜色选择器）、分组（必选），支持删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookTagEditSheet(
    show: Boolean,
    tag: BookTag?,
    groups: List<BookTagGroup>,
    onDismissRequest: () -> Unit,
    onSave: (BookTag) -> Unit,
    onDelete: (BookTag) -> Unit,
    onCreateGroup: suspend (String) -> Long,
) {
    val scope = rememberCoroutineScope()
    val isNew = tag == null || tag.id == 0L
    val initialGroup = remember(tag, groups) {
        if (tag != null && tag.groupId != 0L) groups.find { it.id == tag.groupId }?.name ?: ""
        else groups.firstOrNull()?.name ?: ""
    }
    var name by remember(show, tag) { mutableStateOf(tag?.name ?: "") }
    var selectedGroup by remember(show, tag, groups) { mutableStateOf(initialGroup) }
    var color by remember(show, tag) {
        mutableStateOf(if (tag != null && tag.color != 0) tag.color else 0xFF2196F3.toInt())
    }
    var expandedGroup by remember { mutableStateOf(false) }
    var groupError by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    fun buildTag(groupId: Long): BookTag {
        val base = tag ?: BookTag(name = name.trim(), color = color)
        return base.copy(
            name = name.trim(),
            color = color,
            groupId = groupId,
            updateTime = System.currentTimeMillis()
        )
    }

    fun save() {
        val g = selectedGroup.trim()
        if (g.isBlank()) {
            groupError = true
            return
        }
        groupError = false
        scope.launch {
            val gId = groups.find { it.name == g }?.id ?: onCreateGroup(g)
            onSave(buildTag(gId))
        }
    }

    AppModalBottomSheet(
        title = if (isNew) stringResource(R.string.new_tag) else stringResource(R.string.edit_tag),
        show = show,
        onDismissRequest = onDismissRequest,
        startAction = {
            MediumPlainButton(
                onClick = onDismissRequest,
                icon = Icons.Default.Close,
                contentDescription = stringResource(R.string.cancel)
            )
        },
        endAction = {
            if (!isNew) {
                Box {
                    MediumPlainButton(
                        onClick = { showMenu = true },
                        icon = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.more)
                    )
                    RoundDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        RoundDropdownMenuItem(
                            text = stringResource(R.string.delete_tag),
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = {
                                tag?.let { onDelete(it) }
                                showMenu = false
                            }
                        )
                    }
                }
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            AppTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.tag_name),
                singleLine = true,
                backgroundColor = LegadoTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )

            ExposedDropdownMenuBox(
                expanded = expandedGroup,
                onExpandedChange = { expandedGroup = it }
            ) {
                AppTextField(
                    value = selectedGroup,
                    onValueChange = {
                        selectedGroup = it
                        groupError = false
                    },
                    label = stringResource(R.string.tag_group_required),
                    singleLine = true,
                    readOnly = false,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedGroup) },
                    backgroundColor = LegadoTheme.colorScheme.surface,
                    modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable).fillMaxWidth().padding(bottom = 8.dp)
                )
                ExposedDropdownMenu(
                    expanded = expandedGroup,
                    onDismissRequest = { expandedGroup = false }
                ) {
                    groups.forEach { g ->
                        DropdownMenuItem(
                            text = { Text(g.name) },
                            onClick = {
                                selectedGroup = g.name
                                groupError = false
                                expandedGroup = false
                            }
                        )
                    }
                }
            }
            if (groupError) {
                Text(
                    stringResource(R.string.tag_group_required_hint),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showColorPicker = true }
                    .padding(bottom = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.tag_color),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        AppFloatingActionButton(
            onClick = { save() },
            modifier = Modifier
                .align(Alignment.End)
                .padding(16.dp),
            icon = Icons.Default.Check
        )
    }

    ColorPickerSheet(
        show = showColorPicker,
        initialColor = color,
        onDismissRequest = { showColorPicker = false },
        onColorSelected = { color = it; showColorPicker = false }
    )
}
