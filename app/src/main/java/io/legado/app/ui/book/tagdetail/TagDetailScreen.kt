package io.legado.app.ui.book.tagdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.ui.book.tagmanage.TagEditData
import io.legado.app.ui.book.tagmanage.TagEditSheet
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.dialog.ColorPickerSheet
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.icon.AppIcon
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagDetailScreen(
    state: TagDetailUiState,
    onIntent: (TagDetailIntent) -> Unit,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    var tagEdit by remember { mutableStateOf<TagEditData?>(null) }
    var showColorPicker by remember { mutableStateOf(false) }
    val tag = state.tag

    val tagColor = if (tag != null && tag.color != 0L) {
        Color(tag.color.toInt())
    } else {
        LegadoTheme.colorScheme.primary
    }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = tag?.name ?: "标签详情",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBack)
                },
                actions = {
                    MediumTonalButton(onClick = {
                        tag?.let {
                            tagEdit = TagEditData(id = it.id, name = it.name, groupId = it.groupId, color = it.color)
                        }
                    }, text = "编辑")
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (tag != null) {
                item {
                    TagDetailHeader(
                        name = tag.name,
                        color = tagColor,
                        groupName = state.groupName,
                        bookCount = state.books.size,
                    )
                }
            }
            item {
                Text(
                    "关联书籍（${state.books.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
            }
            if (state.books.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "暂无关联书籍",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(state.books, key = { it.bookUrl }) { book ->
                    BookRow(book = book, color = tagColor, onClick = { onOpenBook(book.bookUrl) })
                }
            }
        }
    }

    tag?.let { currentTag ->
        val tagAliases = remember(state.mappings, currentTag) {
            state.mappings.filter { it.newTagId == currentTag.id }
        }
        TagEditSheet(
            data = tagEdit,
            groups = state.groups,
            aliases = tagAliases,
            onMapToStandard = { name -> onIntent(TagDetailIntent.SetStandard(name)) },
            onRemoveAlias = { mapping -> onIntent(TagDetailIntent.RemoveAlias(mapping.oldTagName)) },
            onChange = { tagEdit = it },
            onConfirm = {
                onIntent(TagDetailIntent.Save(it.name, it.groupId, it.color))
                tagEdit = null
            },
            onExclude = { name ->
                onIntent(TagDetailIntent.Exclude(name))
                tagEdit = null
            },
            onDismiss = { tagEdit = null },
            onPickColor = { showColorPicker = true },
        )
    }

    ColorPickerSheet(
        show = showColorPicker,
        initialColor = (tagEdit?.color ?: tag?.color ?: 0xFF6750A4).toInt(),
        onDismissRequest = { showColorPicker = false },
        onColorSelected = { argb ->
            tagEdit = tagEdit?.copy(color = argb.toUInt().toLong())
            showColorPicker = false
        },
    )
}

@Composable
private fun TagDetailHeader(
    name: String,
    color: Color,
    groupName: String,
    bookCount: Int,
) {
    NormalCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 16.dp,
        containerColor = color.copy(alpha = 0.12f),
        contentColor = LegadoTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "分组：$groupName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                "$bookCount 本",
                style = MaterialTheme.typography.titleMedium,
                color = color,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun BookRow(book: Book, color: Color, onClick: () -> Unit) {
    NormalCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    book.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                )
                Text(
                    book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            AppIcon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
