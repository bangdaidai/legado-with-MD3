package io.legado.app.ui.book.readingmemory.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.ReadingStatistics
import io.legado.app.constant.AppLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingMemoryDetailScreen(
    state: ReadingMemoryDetailUiState,
    onBack: () -> Unit,
    onIntent: (ReadingMemoryDetailIntent) -> Unit,
) {
    val scrollState = rememberScrollState()
    AppLog.put("[阅读记忆] DetailScreen 渲染 loading=${state.loading} bookName=${state.bookName} 进度=${state.progressInfo}")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读记忆", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onIntent(ReadingMemoryDetailIntent.Refresh) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "同步")
                    }
                }
            )
        }
    ) { padding ->
        if (state.loading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 书名/作者/来源
            BookHeaderSection(state)

            // 评分
            RatingSection(
                rating = state.rating,
                onRate = { onIntent(ReadingMemoryDetailIntent.SetRating(it)) }
            )

            // 阅读进度
            ProgressSection(state.progressInfo)

            // 简介
            IntroSection(
                intro = state.intro,
                onEdit = { onIntent(ReadingMemoryDetailIntent.EditIntro(it)) }
            )

            // 书评
            ReviewSection(
                review = state.review,
                onEdit = { onIntent(ReadingMemoryDetailIntent.OpenReviewEditor(state.review)) }
            )

            // 阅读统计
            state.statistics?.let { stats ->
                StatsSection(stats)
            }

            // 主角
            if (state.protagonistNames.isNotEmpty()) {
                ProtagonistsSection(state.protagonistNames)
            }

            // 弃文
            AbandonedSection(
                abandoned = state.abandoned,
                onToggle = { onIntent(ReadingMemoryDetailIntent.ToggleAbandoned(it)) }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 评价编辑弹窗
    if (state.showReviewEditor) {
        AlertDialog(
            onDismissRequest = { onIntent(ReadingMemoryDetailIntent.DismissReviewEditor) },
            title = { Text("编辑书评") },
            text = {
                OutlinedTextField(
                    value = state.reviewDraft,
                    onValueChange = { onIntent(ReadingMemoryDetailIntent.UpdateReviewDraft(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    maxLines = 10,
                    placeholder = { Text("写写你对这本书的感受...") }
                )
            },
            confirmButton = {
                TextButton(onClick = { onIntent(ReadingMemoryDetailIntent.SaveReview) }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(ReadingMemoryDetailIntent.DismissReviewEditor) }) {
                    Text("取消")
                }
            }
        )
    }

    // 弃文确认弹窗
    if (state.showAbandonedDialog) {
        AlertDialog(
            onDismissRequest = { onIntent(ReadingMemoryDetailIntent.DismissAbandonedDialog) },
            title = { Text("确认弃文") },
            text = { Text("确定要将这本书标记为弃文吗？阅读记录仍会保留。") },
            confirmButton = {
                TextButton(onClick = { onIntent(ReadingMemoryDetailIntent.ConfirmAbandoned) }) {
                    Text("确认弃文")
                }
            },
            dismissButton = {
                TextButton(onClick = { onIntent(ReadingMemoryDetailIntent.DismissAbandonedDialog) }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun BookHeaderSection(state: ReadingMemoryDetailUiState) {
    Column {
        Text(
            text = state.bookName.ifBlank { "未知书名" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        if (state.author.isNotBlank()) {
            Text(
                text = state.author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!state.isStillOnShelf) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Text(
                    text = "已从书架删除",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun RatingSection(rating: Int, onRate: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "评分",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(5) { index ->
                    val starNum = index + 1
                    IconButton(
                        onClick = { onRate(starNum) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Text(
                            text = if (starNum <= rating) "\u2605" else "\u2606",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (starNum <= rating) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressSection(progress: String) {
    if (progress.isNotBlank()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "阅读进度",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = progress,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun IntroSection(intro: String, onEdit: (String) -> Unit) {
    if (intro.isNotBlank()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "简介",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(
                        onClick = { onEdit(intro) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.Edit,
                            contentDescription = "编辑",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = intro,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReviewSection(review: String?, onEdit: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "书评",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onEdit) {
                    Text(if (review.isNullOrBlank()) "写书评" else "编辑")
                }
            }
            if (!review.isNullOrBlank()) {
                Text(
                    text = review,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = "暂无书评，点击上方按钮添加",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun StatsSection(stats: ReadingStatistics) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "阅读统计",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("累计时长", formatReadTime(stats.totalReadTime))
                StatItem("阅读天数", "${stats.readingDays} 天")
                StatItem("总字数", formatWordCount(stats.totalWords))
            }
            if (stats.maxDayReadTime > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(
                        "单日最久",
                        formatReadTime(stats.maxDayReadTime) + if (stats.maxDayReadDate != null) {
                            " (${stats.maxDayReadDate})"
                        } else ""
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProtagonistsSection(names: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "主角",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                names.forEach { name ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = name,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AbandonedSection(abandoned: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "阅读状态",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (abandoned) {
                Button(
                    onClick = { onToggle(false) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Text("取消弃文标记")
                }
            } else {
                OutlinedButton(
                    onClick = { onToggle(true) },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("标记为弃文")
                }
            }
        }
    }
}

private fun formatReadTime(millis: Long): String {
    val hours = millis / 3600000
    val minutes = (millis % 3600000) / 60000
    return when {
        hours > 0 -> "$hours 小时 ${minutes} 分钟"
        minutes > 0 -> "$minutes 分钟"
        else -> "不到 1 分钟"
    }
}

private fun formatWordCount(words: Long): String {
    return when {
        words >= 10000 -> "${words / 10000}万"
        words >= 1000 -> "${words / 1000}千"
        else -> "$words"
    }
}
