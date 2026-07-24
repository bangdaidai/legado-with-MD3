package io.legado.app.ui.book.readingmemory

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.coil.compose.AsyncImage
import io.legado.app.constant.ReadingStatus
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.entities.readRecord.ReadRecordTimelineDay
import io.legado.app.ui.book.info.TimelineSessionRow
import io.legado.app.utils.formatReadDuration
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ReadingMemoryDetailRoute(
    bookUrl: String,
    onBack: () -> Unit,
) {
    val viewModel: ReadingMemoryDetailViewModel = koinViewModel(parameters = { parametersOf(bookUrl) })
    val book by viewModel.bookFlow.collectAsState(initial = null)
    val memory by viewModel.memoryFlow.collectAsState(initial = null)
    val timelineDays by viewModel.timelineDays.collectAsState()
    val readTime by viewModel.readTime.collectAsState()
    val excerpts by viewModel.excerpts.collectAsState()

    ReadingMemoryDetailScreen(
        book = book,
        memory = memory,
        timelineDays = timelineDays,
        readTime = readTime,
        excerpts = excerpts,
        onBack = onBack,
        onStatusSelected = viewModel::setReadingStatus,
        onRatingSelected = viewModel::setRating,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingMemoryDetailScreen(
    book: Book?,
    memory: ReadingMemory?,
    timelineDays: List<ReadRecordTimelineDay>,
    readTime: Long,
    excerpts: List<Bookmark>,
    onBack: () -> Unit,
    onStatusSelected: (ReadingStatus) -> Unit,
    onRatingSelected: (Float) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val status = memory?.getStatus() ?: ReadingStatus.PENDING
    val rating = memory?.rating ?: 0f
    val progress = memory?.progress
        ?: book?.let { if (it.totalChapterNum > 0) it.durChapterIndex.toFloat() / it.totalChapterNum else 0f }
        ?: 0f

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(book?.name ?: "阅读记忆") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                    progress = progress,
                    onStatusSelected = onStatusSelected,
                    onRatingSelected = onRatingSelected,
                )
            }
            item {
                ReadingStats(readTime = readTime, excerptCount = excerpts.size)
            }
            item {
                TimelineSection(timelineDays = timelineDays)
            }
            item {
                ExcerptSection(excerpts = excerpts)
            }
        }
    }
}

@Composable
private fun ReadingMemoryHeader(
    book: Book?,
    status: ReadingStatus,
    rating: Float,
    progress: Float,
    onStatusSelected: (ReadingStatus) -> Unit,
    onRatingSelected: (Float) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp)) {
            AsyncImage(
                model = book?.getDisplayCover(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .width(84.dp)
                    .height(116.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    book?.name ?: "",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    book?.author ?: "",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ReadingStatus.entries.forEach { s ->
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
                    "${(progress * 100).toInt()}%",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
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
                        androidx.compose.material3.MaterialTheme.colorScheme.primary
                    } else {
                        androidx.compose.material3.MaterialTheme.colorScheme.outline
                    },
                )
            }
        }
    }
}

@Composable
private fun ReadingStats(readTime: Long, excerptCount: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatCard(
            label = "阅读时长",
            value = formatReadDuration(readTime),
            modifier = Modifier.weight(1f),
        )
        StatCard(
            label = "书摘",
            value = "$excerptCount",
            modifier = Modifier.weight(1f),
        )
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
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                label,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TimelineSection(timelineDays: List<ReadRecordTimelineDay>) {
    Column {
        SectionTitle("阅读时间线")
        if (timelineDays.isEmpty()) {
            EmptyHint("暂无阅读记录")
        } else {
            timelineDays.forEach { day ->
                Text(
                    day.date,
                    style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                day.sessions.forEach { session ->
                    TimelineSessionRow(session = session)
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
                    style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                )
            }
            if (bookmark.bookText.isNotBlank()) {
                Text(
                    "“${bookmark.bookText}”",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (bookmark.content.isNotBlank()) {
                Text(
                    bookmark.content,
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
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
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
    }
}

// items 需要显式导入，避免与 Column 作用域内建冲突
// 此处 ExcerptSection 使用 LazyColumn 的 items，由调用方所在 LazyColumn 提供
