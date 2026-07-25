package io.legado.app.ui.book.readingmemory

import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.sp
import io.coil.compose.AsyncImage
import io.legado.app.constant.ReadingStatus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProtagonist
import io.legado.app.data.entities.BookReview
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.utils.formatReadDuration
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun ReadingMemoryDetailRoute(
    bookUrl: String,
    onBack: () -> Unit,
) {
    val viewModel: ReadingMemoryDetailViewModel = koinViewModel(parameters = { parametersOf(bookUrl) })
    val uiState by viewModel.uiState.collectAsState(initial = ReadingMemoryDetailUiState())
    val protagonists by viewModel.protagonists.collectAsState(initial = emptyList())
    val reviews by viewModel.reviews.collectAsState(initial = emptyList())
    val excerpts by viewModel.excerpts.collectAsState(initial = emptyList())
    val context = androidx.compose.ui.platform.LocalContext.current

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.toastEvents.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    ReadingMemoryDetailScreen(
        uiState = uiState,
        protagonists = protagonists,
        reviews = reviews,
        excerpts = excerpts,
        onBack = onBack,
        onStatusSelected = viewModel::setReadingStatus,
        onRatingSelected = viewModel::setRating,
        onAddTag = viewModel::addTag,
        onRemoveTag = viewModel::removeTag,
        onAddProtagonist = viewModel::addProtagonist,
        onRemoveProtagonist = viewModel::removeProtagonist,
        onExtractProtagonists = viewModel::extractProtagonists,
        onAddReview = viewModel::addReview,
        onUpdateReview = viewModel::updateReview,
        onDeleteReview = viewModel::deleteReview,
        onEditBook = {
            context.startActivity(
                Intent(context, BookInfoActivity::class.java).apply {
                    putExtra("bookUrl", bookUrl)
                },
            )
        },
        onShareBookplate = { shareBookplate(context, uiState) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingMemoryDetailScreen(
    uiState: ReadingMemoryDetailUiState,
    protagonists: List<BookProtagonist>,
    reviews: List<BookReview>,
    excerpts: List<Bookmark>,
    onBack: () -> Unit,
    onStatusSelected: (ReadingStatus) -> Unit,
    onRatingSelected: (Float) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (BookTag) -> Unit,
    onAddProtagonist: (String) -> Unit,
    onRemoveProtagonist: (Long) -> Unit,
    onExtractProtagonists: () -> Unit,
    onAddReview: (String) -> Unit,
    onUpdateReview: (BookReview, String) -> Unit,
    onDeleteReview: (Long) -> Unit,
    onEditBook: () -> Unit,
    onShareBookplate: () -> Unit,
) {
    val book = uiState.book
    val memory = uiState.memory
    val status = memory?.getStatus() ?: ReadingStatus.PENDING
    val rating = memory?.rating ?: 0f
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var tagDialogOpen by remember { mutableStateOf(false) }
    var protagonistDialogOpen by remember { mutableStateOf(false) }
    var reviewDialogOpen by remember { mutableStateOf(false) }
    var reviewDialogEdit by remember { mutableStateOf<BookReview?>(null) }
    var tagToRemove by remember { mutableStateOf<BookTag?>(null) }
    var protagonistToRemove by remember { mutableStateOf<BookProtagonist?>(null) }
    var reviewToDelete by remember { mutableStateOf<BookReview?>(null) }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(book?.name ?: "阅读记忆") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onEditBook) {
                        Icon(Icons.Default.Edit, contentDescription = "编辑书籍信息")
                    }
                    IconButton(onClick = onShareBookplate) {
                        Icon(Icons.Default.Share, contentDescription = "藏书票")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                ReadingMemoryHeader(
                    book = book,
                    status = status,
                    rating = rating,
                    memory = memory,
                    onStatusSelected = onStatusSelected,
                    onRatingSelected = onRatingSelected,
                )
            }
            item {
                ReadingDataSection(uiState = uiState, excerptsCount = excerpts.size, reviewsCount = reviews.size)
            }
            item {
                ReadingSessionsSection(months = uiState.sessionsByMonth)
            }
            item {
                ExcerptSection(excerpts = excerpts)
            }
            item {
                ReviewSection(
                    reviews = reviews,
                    onAdd = { reviewDialogEdit = null; reviewDialogOpen = true },
                    onEdit = { reviewDialogEdit = it; reviewDialogOpen = true },
                    onDelete = { reviewToDelete = it },
                )
            }
            item {
                TagSection(
                    tags = uiState.tags,
                    onAdd = { tagDialogOpen = true },
                    onRemove = { tagToRemove = it },
                )
            }
            item {
                ProtagonistSection(
                    protagonists = protagonists,
                    onAdd = { protagonistDialogOpen = true },
                    onExtract = onExtractProtagonists,
                    onRemove = { protagonistToRemove = it },
                )
            }
        }
    }

    if (tagDialogOpen) {
        InputDialog(
            title = "添加标签",
            placeholder = "输入标签名",
            onDismiss = { tagDialogOpen = false },
            onConfirm = { text ->
                onAddTag(text)
                tagDialogOpen = false
            },
        )
    }
    if (protagonistDialogOpen) {
        InputDialog(
            title = "添加主角",
            placeholder = "输入主角名",
            onDismiss = { protagonistDialogOpen = false },
            onConfirm = { text ->
                onAddProtagonist(text)
                protagonistDialogOpen = false
            },
        )
    }
    if (reviewDialogOpen) {
        ReviewDialog(
            review = reviewDialogEdit,
            onDismiss = { reviewDialogOpen = false },
            onConfirm = { text ->
                if (reviewDialogEdit != null) {
                    onUpdateReview(reviewDialogEdit!!, text)
                } else {
                    onAddReview(text)
                }
                reviewDialogOpen = false
            },
        )
    }
    if (tagToRemove != null) {
        ConfirmDialog(
            title = "移除标签",
            text = "确定移除标签「${tagToRemove!!.name}」？",
            onDismiss = { tagToRemove = null },
            onConfirm = { onRemoveTag(tagToRemove!!); tagToRemove = null },
        )
    }
    if (protagonistToRemove != null) {
        ConfirmDialog(
            title = "移除主角",
            text = "确定移除主角「${protagonistToRemove!!.name}」？",
            onDismiss = { protagonistToRemove = null },
            onConfirm = { onRemoveProtagonist(protagonistToRemove!!.id); protagonistToRemove = null },
        )
    }
    if (reviewToDelete != null) {
        ConfirmDialog(
            title = "删除书评",
            text = "确定删除这条书评？",
            onDismiss = { reviewToDelete = null },
            onConfirm = { onDeleteReview(reviewToDelete!!.id); reviewToDelete = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingMemoryHeader(
    book: Book?,
    status: ReadingStatus,
    rating: Float,
    memory: ReadingMemory?,
    onStatusSelected: (ReadingStatus) -> Unit,
    onRatingSelected: (Float) -> Unit,
) {
    var introExpanded by remember { mutableStateOf(false) }
    val intro = book?.getDisplayIntro().orEmpty()
    val progress = memory?.progress
        ?: book?.let { if (it.totalChapterNum > 0) it.durChapterIndex.toFloat() / it.totalChapterNum else 0f }
        ?: 0f
    val finished = status == ReadingStatus.FINISHED && (memory?.finishReadTime ?: 0L) > 0

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp)) {
            AsyncImage(
                model = book?.getDisplayCover(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(84.dp)
                    .height(116.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    book?.name ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    book?.author ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ReadingStatus.values().forEach { s ->
                        FilterChip(
                            selected = s == status,
                            onClick = { onStatusSelected(s) },
                            label = { Text(s.displayName) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                RatingBar(rating = rating, onRatingSelected = onRatingSelected)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (finished) {
                        "读完于 ${formatDay(memory?.finishReadTime ?: 0L)}"
                    } else {
                        "${(progress * 100).toInt()}%"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
                if (intro.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        intro,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (introExpanded) Int.MAX_VALUE else 3,
                        modifier = Modifier.clickable { introExpanded = !introExpanded },
                    )
                }
            }
        }
    }
}

@Composable
private fun RatingBar(rating: Float, onRatingSelected: (Float) -> Unit) {
    Row {
        for (i in 1..5) {
            val filled = i <= rating
            IconButton(
                onClick = { onRatingSelected(i.toFloat()) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = if (filled) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    tint = if (filled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
        }
    }
}

@Composable
private fun ReadingDataSection(
    uiState: ReadingMemoryDetailUiState,
    excerptsCount: Int,
    reviewsCount: Int,
) {
    val stats = uiState.stats
    val items = listOf(
        "总阅读时长" to formatReadDuration(stats.totalReadTime),
        "阅读天数" to "${stats.readingDays}天",
        "阅读进度" to "${stats.progressPercent}%",
        "已读章节" to if (stats.totalChapterNum > 0) "${stats.readChapterIndex}/${stats.totalChapterNum}" else "—",
        "单日最久" to if (stats.maxDayReadTime > 0) "${formatReadDuration(stats.maxDayReadTime)}\n${formatDay(stats.maxDayReadDate)}" else "—",
        "总阅读字数" to stats.totalReadWordsWan?.let { String.format(Locale.CHINA, "%.1f万字", it) } ?: "—",
        "剩余字数" to stats.remainingWordsWan?.let { String.format(Locale.CHINA, "%.1f万字", it) } ?: "—",
        "字数" to stats.wordCountText.ifBlank { "未知" },
        "状态" to stats.kindText,
        "上次阅读" to stats.lastReadText,
        "开始阅读" to stats.firstReadText,
        "书摘" to "$excerptsCount",
        "书评" to "$reviewsCount",
    )
    Column {
        SectionTitle("阅读数据")
        Spacer(Modifier.height(8.dp))
        val chunked = items.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            chunked.forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowItems.forEach { (label, value) ->
                        StatCard(label = label, value = value, modifier = Modifier.weight(1f))
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReadingSessionsSection(months: List<MonthReadingSessions>) {
    val expanded = remember { mutableStateListOf<String>() }
    Column {
        SectionTitle("阅读会话")
        if (months.isEmpty()) {
            EmptyHint("暂无阅读记录")
        } else {
            months.forEach { month ->
                val isExpanded = expanded.contains(month.monthTitle)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            if (isExpanded) expanded.remove(month.monthTitle) else expanded.add(month.monthTitle)
                        },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                month.monthTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    formatReadDuration(month.totalTime),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Icon(
                                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                )
                            }
                        }
                        if (isExpanded) {
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            month.days.forEach { day ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        formatDay(day.date),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        formatReadDuration(day.time),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExcerptSection(excerpts: List<Bookmark>) {
    Column {
        SectionTitle("书摘（带笔记的书签）")
        if (excerpts.isEmpty()) {
            EmptyHint("暂无书摘")
        } else {
            excerpts.forEach { bm ->
                ExcerptCard(bookmark = bm)
            }
        }
    }
}

@Composable
private fun ExcerptCard(bookmark: Bookmark) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (bookmark.chapterName.isNotBlank()) {
                Text(
                    bookmark.chapterName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (bookmark.bookText.isNotBlank()) {
                Text(
                    "“${bookmark.bookText}”",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (bookmark.content.isNotBlank()) {
                Text(
                    bookmark.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ReviewSection(
    reviews: List<BookReview>,
    onAdd: () -> Unit,
    onEdit: (BookReview) -> Unit,
    onDelete: (BookReview) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("书评")
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "添加书评")
            }
        }
        if (reviews.isEmpty()) {
            EmptyHint("暂无书评")
        } else {
            reviews.forEach { review ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onEdit(review) },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            review.reviewContent,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                formatDay(review.updateTime),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            IconButton(
                                onClick = { onDelete(review) },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除书评",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagSection(
    tags: List<BookTag>,
    onAdd: () -> Unit,
    onRemove: (BookTag) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("标签")
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = "添加标签")
            }
        }
        if (tags.isEmpty()) {
            EmptyHint("暂无标签")
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.forEach { tag ->
                    FilterChip(
                        selected = false,
                        onClick = { onRemove(tag) },
                        label = { Text(tag.name) },
                        trailingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtagonistSection(
    protagonists: List<BookProtagonist>,
    onAdd: () -> Unit,
    onExtract: () -> Unit,
    onRemove: (BookProtagonist) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("主角")
            Row {
                TextButton(onClick = onExtract) { Text("提取") }
                IconButton(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = "添加主角")
                }
            }
        }
        if (protagonists.isEmpty()) {
            EmptyHint("暂无主角，可手动添加或从简介提取")
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                protagonists.forEach { p ->
                    FilterChip(
                        selected = false,
                        onClick = { onRemove(p) },
                        label = { Text(p.name) },
                        trailingIcon = {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InputDialog(
    title: String,
    placeholder: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            )
        },
    )
}

@Composable
private fun ReviewDialog(
    review: BookReview?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(review?.reviewContent ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(if (review != null) "编辑书评" else "添加书评") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("写点什么…") },
                minLines = 3,
                maxLines = 6,
            )
        },
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(title) },
        text = { Text(text) },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

private val DAY_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

private fun formatDay(time: Long): String {
    if (time <= 0) return "—"
    return DAY_FORMAT.format(java.util.Date(time))
}

private fun shareBookplate(context: android.content.Context, uiState: ReadingMemoryDetailUiState) {
    val book = uiState.book
    val stats = uiState.stats
    val sb = StringBuilder()
    sb.append("📖 ${book?.name ?: "未知书籍"}\n")
    sb.append("作者：${book?.author ?: "未知"}\n")
    sb.append("状态：${uiState.memory?.getReadingStatusTag() ?: "待读"}\n")
    sb.append("评分：${String.format(Locale.CHINA, "%.1f", uiState.memory?.rating ?: 0f)}\n")
    sb.append("总阅读时长：${formatReadDuration(stats.totalReadTime)}\n")
    sb.append("阅读天数：${stats.readingDays}天\n")
    sb.append("阅读进度：${stats.progressPercent}%\n")
    if (stats.totalReadWordsWan != null) sb.append("总阅读字数：${String.format(Locale.CHINA, "%.1f万字", stats.totalReadWordsWan)}\n")
    if (uiState.tags.isNotEmpty()) sb.append("标签：${uiState.tags.joinToString("、") { it.name }}\n")
    if (uiState.protagonists.isNotEmpty()) sb.append("主角：${uiState.protagonists.joinToString("、") { it.name }}\n")
    if (uiState.reviews.isNotEmpty()) sb.append("书评：${uiState.reviews.size}条\n")

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, sb.toString())
        putExtra(Intent.EXTRA_TITLE, "藏书票 · ${book?.name ?: ""}")
    }
    context.startActivity(Intent.createChooser(intent, "分享藏书票"))
}
