package io.legado.app.ui.book.tag

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.BookTag
import io.legado.app.data.repository.BookTagRelationRepository
import io.legado.app.data.repository.BookTagRepository
import io.legado.app.data.repository.ExcludedTagRepository
import io.legado.app.help.book.TagManager
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppFloatingActionButton
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.button.series.MediumPlainButton
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.utils.TagColorUtils
import kotlinx.coroutines.launch

internal val TAG_PALETTE = listOf(
    0xFFE57373.toInt(), 0xFFF06292.toInt(), 0xFFBA68C8.toInt(),
    0xFF9575CD.toInt(), 0xFF7986CB.toInt(), 0xFF64B5F6.toInt(),
    0xFF4FC3F7.toInt(), 0xFF4DD0E1.toInt(), 0xFF4DB6AC.toInt(),
    0xFF81C784.toInt(), 0xFFAED581.toInt(), 0xFFFFD54F.toInt(),
    0xFFFF8A65.toInt(), 0xFFA1887F.toInt(), 0xFF90A4AE.toInt(),
    0xFF607D8B.toInt()
)

private fun tagColorInt(tag: BookTag): Int =
    if (tag.color != 0) tag.color else TagColorUtils.generateRandomColor(tag.name)

private fun textColorFor(bg: Color): Color {
    val luminance = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
    return if (luminance > 0.6) Color(0xFF212121) else Color.White
}

@Composable
private fun TagChip(
    tag: BookTag,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = Color(tagColorInt(tag))
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bg,
        modifier = Modifier
            .padding(4.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = tag.name,
                color = textColorFor(bg),
                fontWeight = FontWeight.Medium
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = textColorFor(bg),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(16.dp)
                )
            }
        }
    }
}

/**
 * 书籍信息编辑中的标签选择区：展示已选标签、进入选择面板、进入标签库管理。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookTagSection(
    bookUrl: String,
    onManageClick: () -> Unit,
    onSelectClick: () -> Unit,
) {
    val selectedTags by BookTagRelationRepository.observeTagsByBook(bookUrl)
        .collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "标签库",
                style = LegadoTheme.typography.titleMedium,
                color = LegadoTheme.colorScheme.onSurface
            )
            MediumPlainButton(
                onClick = onManageClick,
                text = "管理标签库",
                icon = Icons.Default.Label
            )
        }
        if (selectedTags.isEmpty()) {
            Text(
                text = "未选择标签",
                color = LegadoTheme.colorScheme.outline,
                style = LegadoTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        } else {
            FlowRow(modifier = Modifier.fillMaxWidth()) {
                selectedTags.forEach { tag ->
                    TagChip(tag = tag, selected = true, onClick = onSelectClick)
                }
            }
        }
        MediumPlainButton(
            onClick = onSelectClick,
            text = "选择 / 新增标签",
            icon = Icons.Default.Add
        )
    }
}

/**
 * 标签选择底部面板：多选标签库中的标签，或在面板内直接新建并关联。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookTagSelectSheet(
    show: Boolean,
    bookUrl: String,
    onDismissRequest: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val allTags by BookTagRepository.observeAll().collectAsState(initial = emptyList())
    val excluded by ExcludedTagRepository.observeAll().collectAsState(initial = emptyList())
    val excludedNames = excluded.map { it.name }.toSet()
    val visibleTags = allTags.filter { it.name !in excludedNames }
    val selectedTags by BookTagRelationRepository.observeTagsByBook(bookUrl)
        .collectAsState(initial = emptyList())
    val selectedIds = selectedTags.map { it.id }.toSet()

    var newName by remember(show) { mutableStateOf("") }
    var newColor by remember(show) { mutableStateOf(TAG_PALETTE.random()) }

    fun toggle(tag: BookTag) {
        scope.launch {
            if (selectedIds.contains(tag.id)) {
                TagManager.removeTagFromBook(bookUrl, tag.id)
            } else {
                TagManager.addTagToBook(bookUrl, tag.id)
            }
        }
    }

    fun createAndAdd() {
        val name = newName.trim()
        if (name.isEmpty()) return
        scope.launch {
            val tag = TagManager.ensureTag(name, newColor)
            TagManager.addTagToBook(bookUrl, tag.id)
            newName = ""
        }
    }

    AppModalBottomSheet(
        title = "选择标签",
        show = show,
        onDismissRequest = onDismissRequest,
        startAction = {
            MediumPlainButton(
                onClick = onDismissRequest,
                icon = Icons.Default.Close,
                contentDescription = "关闭"
            )
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                AppTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = "新建标签名称",
                    singleLine = true,
                    backgroundColor = LegadoTheme.colorScheme.surface,
                    modifier = Modifier.weight(1f)
                )
                MediumPlainButton(
                    onClick = { createAndAdd() },
                    icon = Icons.Default.Add,
                    contentDescription = "添加"
                )
            }
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(TAG_PALETTE) { c ->
                    val selected = c == newColor
                    Box(
                        modifier = Modifier
                            .size(if (selected) 36.dp else 28.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .clickable { newColor = c }
                    )
                }
            }
            if (allTags.isEmpty()) {
                Text(
                    text = "暂无标签，可在上方新建",
                    color = LegadoTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                FlowRow(modifier = Modifier.fillMaxWidth()) {
                    visibleTags.forEach { tag ->
                        TagChip(
                            tag = tag,
                            selected = selectedIds.contains(tag.id),
                            onClick = { toggle(tag) }
                        )
                    }
                }
                if (excludedNames.isNotEmpty()) {
                    Text(
                        text = "已排除 ${excludedNames.size} 个标签（不显示）",
                        color = LegadoTheme.colorScheme.outline,
                        style = LegadoTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
        AppFloatingActionButton(
            onClick = onDismissRequest,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            icon = Icons.Default.Check
        )
    }
}
