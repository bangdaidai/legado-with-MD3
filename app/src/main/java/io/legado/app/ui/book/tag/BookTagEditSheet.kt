package io.legado.app.ui.book.tag

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.button.series.MediumPlainButton
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import kotlinx.coroutines.launch

/**
 * 标签编辑底部面板：名称、颜色、分组，支持删除。
 */
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
        if (tag != null && tag.groupId != 0L) groups.find { it.id == tag.groupId }?.name ?: "" else ""
    }
    var name by remember(show, tag) { mutableStateOf(tag?.name ?: "") }
    var groupName by remember(show, tag) { mutableStateOf(initialGroup) }
    var color by remember(show, tag) {
        mutableStateOf(if (tag != null && tag.color != 0) tag.color else TAG_PALETTE.random())
    }
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
        scope.launch {
            val gId = if (groupName.isBlank()) 0L
            else groups.find { it.name == groupName.trim() }?.id ?: onCreateGroup(groupName.trim())
            onSave(buildTag(gId))
        }
    }

    AppModalBottomSheet(
        title = if (isNew) "新建标签" else "编辑标签",
        show = show,
        onDismissRequest = onDismissRequest,
        startAction = {
            MediumPlainButton(
                onClick = onDismissRequest,
                icon = Icons.Default.Close,
                contentDescription = "关闭"
            )
        },
        endAction = {
            if (!isNew) {
                Box {
                    MediumPlainButton(
                        onClick = { showMenu = true },
                        icon = Icons.Default.MoreVert,
                        contentDescription = "更多"
                    )
                    RoundDropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        RoundDropdownMenuItem(
                            text = "删除标签",
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
                label = "标签名称",
                singleLine = true,
                backgroundColor = LegadoTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
            AppTextField(
                value = groupName,
                onValueChange = { groupName = it },
                label = "分组（可留空或输入新分组名）",
                singleLine = true,
                backgroundColor = LegadoTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(TAG_PALETTE) { c ->
                    val selected = c == color
                    Box(
                        modifier = Modifier
                            .size(if (selected) 36.dp else 28.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .clickable { color = c }
                    )
                }
            }
        }
        AppFloatingActionButton(
            onClick = { save() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            icon = Icons.Default.Check
        )
    }
}
