package io.legado.app.ui.book.tag

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookTag
import io.legado.app.ui.theme.AppTheme
import io.legado.app.ui.theme.AppUiConfiguration
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.image.cover.CoilBookCover
import kotlinx.coroutines.launch

/**
 * 标签详情页（F1）
 * 展示标签信息与其下书籍列表，并通过 [BookTagEditSheet] 进入编辑。
 */
@Composable
fun BookTagDetailScreen(
    viewModel: BookTagDetailViewModel,
    tagId: Long,
    onBack: () -> Unit,
) {
    val tag by viewModel.tag.collectAsState(initial = null)
    val groupName by viewModel.groupName.collectAsState(initial = "")
    val books by viewModel.books.collectAsState(initial = emptyList())
    val groups by viewModel.groups.collectAsState(initial = emptyList())

    var showEdit by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BookTag?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tagId) { viewModel.load(tagId) }

    BookTagDetailContent(
        tag = tag,
        groupName = groupName,
        books = books,
        onBack = onBack,
        onEdit = {
            editing = tag
            showEdit = true
        }
    )

    if (showEdit) {
        BookTagEditSheet(
            show = true,
            tag = editing,
            groups = groups,
            onDismissRequest = { showEdit = false },
            onSave = {
                scope.launch {
                    viewModel.saveTag(it)
                    viewModel.load(tagId)
                    showEdit = false
                }
            },
            onDelete = {
                scope.launch {
                    viewModel.deleteTag(it)
                    showEdit = false
                    onBack()
                }
            },
            onCreateGroup = { scope.launch { viewModel.createGroup(it) } }
        )
    }
}

/**
 * 纯展示层：不依赖 ViewModel / 数据库，供 [BookTagDetailScreen] 与 @Preview 复用。
 */
@Composable
private fun BookTagDetailContent(
    tag: BookTag?,
    groupName: String,
    books: List<Book>,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = tag?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TagInfoCard(tag = tag, groupName = groupName, bookCount = books.size)
            Text(
                text = "包含书籍（${books.size}）",
                style = LegadoTheme.typography.titleMedium
            )
            if (books.isEmpty()) {
                Text(
                    text = "该标签下暂无书籍",
                    style = LegadoTheme.typography.bodyMedium,
                    color = LegadoTheme.colorScheme.outline
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    books.forEach { book -> TagBookRow(book = book) }
                }
            }
        }
    }
}

@Composable
private fun TagInfoCard(tag: BookTag?, groupName: String, bookCount: Int) {
    val color = if (tag != null && tag.color != 0) Color(tag.color) else LegadoTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LegadoTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = tag?.name ?: "",
                style = LegadoTheme.typography.titleLarge
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "分组：$groupName",
                style = LegadoTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.outline
            )
            Text(
                text = "书籍数：$bookCount",
                style = LegadoTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.outline
            )
            val created = tag?.createTime ?: 0L
            if (created > 0) {
                Text(
                    text = "创建：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(created))}",
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun TagBookRow(book: Book) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (LocalInspectionMode.current) {
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 68.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(LegadoTheme.colorScheme.surfaceContainerLow)
            )
        } else {
            CoilBookCover(
                name = book.name,
                author = book.author,
                path = book.getDisplayCover(),
                modifier = Modifier.size(width = 48.dp, height = 68.dp)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = book.name,
                style = LegadoTheme.typography.titleSmall,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = book.author,
                style = LegadoTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.outline,
                maxLines = 1
            )
            if (book.totalChapterNum > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "进度 ${book.durChapterIndex + 1}/${book.totalChapterNum}",
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.outline,
                    maxLines = 1
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "标签详情")
@Composable
private fun BookTagDetailScreenPreview() {
    AppTheme(AppUiConfiguration()) {
        BookTagDetailContent(
            tag = BookTag(id = 1, name = "言情", color = 0xFFFF8A80.toInt(), groupId = 1),
            groupName = "题材",
            books = listOf(
                Book(name = "示例书名一", author = "作者A", totalChapterNum = 100, durChapterIndex = 20),
                Book(name = "示例书名二", author = "作者B", totalChapterNum = 50, durChapterIndex = 0)
            ),
            onBack = {},
            onEdit = {}
        )
    }
}
