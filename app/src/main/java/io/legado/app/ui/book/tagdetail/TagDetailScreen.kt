package io.legado.app.ui.book.tagdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.Book
import io.legado.app.ui.book.tagmanage.TagEditData
import io.legado.app.ui.book.tagmanage.TagEditSheet
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.GlassCard
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.dialog.ColorPickerSheet
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .adaptiveHorizontalPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = adaptiveContentPadding()),
        ) {
            if (tag != null) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (tag.color == 0L) LegadoTheme.colorScheme.primary else Color(tag.color)),
                        )
                        Text(
                            "分组：${state.groupName}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
            item {
                Text(
                    "关联书籍（${state.books.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(state.books) { book ->
                GlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    onClick = { onOpenBook(book.bookUrl) },
                ) {
                    BookRow(book)
                }
            }
        }
    }

    tag?.let {
        TagEditSheet(
            data = tagEdit,
            groups = state.groups,
            onValueChange = { tagEdit = it },
            onSave = {
                onIntent(TagDetailIntent.Save(it.name, it.groupId, it.color))
                tagEdit = null
            },
            onDelete = {
                onIntent(TagDetailIntent.Delete)
                tagEdit = null
            },
            onDismiss = { tagEdit = null },
            onChooseColor = { showColorPicker = true },
        )
    }

    ColorPickerSheet(
        show = showColorPicker,
        initialColor = (tagEdit?.color ?: tag?.color ?: 0xFF6750A4).toInt(),
        onDismissRequest = { showColorPicker = false },
        onColorSelected = { argb ->
            tagEdit = tagEdit?.copy(color = argb.toLong())
            showColorPicker = false
        },
    )
}

@Composable
private fun BookRow(book: Book) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(book.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                book.author,
                style = MaterialTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
