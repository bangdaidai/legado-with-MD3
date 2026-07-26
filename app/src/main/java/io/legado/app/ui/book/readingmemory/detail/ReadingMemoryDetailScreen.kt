package io.legado.app.ui.book.readingmemory.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import io.legado.app.ui.widget.components.image.cover.BookCoverImage
import io.legado.app.ui.widget.components.AppLinearProgressIndicator

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
                title = { Text(state.bookName.ifBlank { "阅读记忆" }, maxLines = 1) },
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
            // 基本信息卡：封面 + 书名/作者/状态/星级 + 字数 + 进度条
            BasicInfoSection(
                state = state,
                onRate = { onIntent(ReadingMemoryDetailIntent.SetRating(it)) }
            )

            // 简介
            IntroSection(
                intro = state.intro,
                onEdit = { onIntent(ReadingMemoryDetailIntent.EditIntro(it)) }
            )

            // 书籍标签
            if (state.tags.isNotEmpty()) {
                TagsSection(state.tags)
            }

            // 主角
            if (state.protagonistNames.isNotEmpty()) {
                ProtagonistsSection(state.protagonistNames)
            }

            // 阅读数据（主 + 次）
            state.statistics?.let { stats ->
                StatsSection(
                    stats = stats,
                    progress = state.progress,
                    progressInfo = state.progressInfo,
                    annotationCount = state.annotationCount,
                    lastReadTime = state.lastReadTime,
                )
            }

            // 书摘（含笔记的书签）
            ExcerptsSection(state.excerpts)

            // 书评
            ReviewSection(
                review = state.review,
                onEdit = { onIntent(ReadingMemoryDetailIntent.OpenReviewEditor(state.review)) }
            )

            // 阅读会话（按天）
            ReadingSessionSection(state.sessions)

            // 阅读状态（弃文切换）
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
private fun BasicInfoSection(state: ReadingMemoryDetailUiState, onRate: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                // 封面
                BookCoverImage(
                    name = state.bookName.ifBlank { null },
                    author = state.author.ifBlank { null },
                    path = state.coverUrl,
                    modifier = Modifier
                        .width(84.dp)
                        .aspectRatio(0.75f)
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    Text(
                        text = state.bookName.ifBlank { "未知书名" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.author.isNotBlank()) {
                        Text(
                            text = state.author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    // 阅读状态彩色徽章
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = statusBadgeColor(state.status),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            text = state.statusText,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = statusBadgeTextColor(state.status)
                        )
                    }
                    // 星级
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        repeat(5) { index ->
                            val starNum = index + 1
                            Text(
                                text = if (starNum <= state.rating) "★" else "☆",
                                style = MaterialTheme.typography.titleMedium,
                                color = if (starNum <= state.rating) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.clickable { onRate(starNum) }.padding(2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 字数
            if (state.wordCountText.isNotBlank()) {
                Text(
                    text = state.wordCountText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (state.wordCount > 0) {
                Text(
                    text = formatWordCount(state.wordCount) + "字",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 进度条
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppLinearProgressIndicator(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp),
                    progress = state.progress.coerceIn(0f, 1f)
                )
                Text(
                    text = "${(state.progress.coerceIn(0f, 1f) * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (state.progressInfo.isNotBlank()) {
                Text(
                    text = state.progressInfo,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
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
                        text = "内容简介",
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
private fun TagsSection(tags: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "书籍标签",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                tags.forEach { tag ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = tag,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                names.forEach { name ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = name,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsSection(
    stats: ReadingStatistics,
    progress: Float,
    progressInfo: String,
    annotationCount: Int,
    lastReadTime: Long,
) {
    val days = if (stats.readingDays > 0) stats.readingDays else 1
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "阅读数据",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StatItem2(
                    primary = formatReadTime(stats.totalReadTime),
                    secondary = "最近 " + formatDate(lastReadTime),
                    label = "累计时长",
                    modifier = Modifier.fillMaxWidth(0.5f)
                )
                StatItem2(
                    primary = "${stats.readingDays} 天",
                    secondary = "共读书",
                    label = "阅读天数",
                    modifier = Modifier.fillMaxWidth(0.5f)
                )
                StatItem2(
                    primary = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%",
                    secondary = progressInfo.ifBlank { "第 0 章" },
                    label = "阅读进度",
                    modifier = Modifier.fillMaxWidth(0.5f)
                )
                StatItem2(
                    primary = "$annotationCount 条",
                    secondary = "含笔记书签",
                    label = "笔记",
                    modifier = Modifier.fillMaxWidth(0.5f)
                )
                StatItem2(
                    primary = formatReadTime(stats.maxDayReadTime),
                    secondary = stats.maxDayReadDate ?: "—",
                    label = "单日最久",
                    modifier = Modifier.fillMaxWidth(0.5f)
                )
                StatItem2(
                    primary = formatWordCount(stats.totalWords),
                    secondary = "日均 " + formatWordCount(stats.totalWords / days),
                    label = "阅读总字数",
                    modifier = Modifier.fillMaxWidth(0.5f)
                )
            }
        }
    }
}

@Composable
private fun StatItem2(
    primary: String,
    secondary: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = primary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = secondary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExcerptsSection(excerpts: List<ReadingMemoryExcerpt>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "书摘（含笔记）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (excerpts.isEmpty()) {
                Text(
                    text = "暂无含笔记的书签。在正文长按添加带笔记的书签后，这里会自动汇总。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            } else {
                excerpts.forEachIndexed { index, excerpt ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    if (excerpt.chapterName.isNotBlank()) {
                        Text(
                            text = excerpt.chapterName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (excerpt.note.isNotBlank()) {
                        Text(
                            text = excerpt.note,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (!excerpt.originText.isNullOrBlank()) {
                        Text(
                            text = excerpt.originText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
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
private fun ReadingSessionSection(sessions: List<ReadingSessionItem>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "阅读会话",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (sessions.isEmpty()) {
                Text(
                    text = "暂无阅读会话记录。开始阅读后，按天的阅读时长会自动汇总到这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            } else {
                sessions.forEachIndexed { index, session ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = session.date.ifBlank { "未知日期" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text(
                                text = formatReadTime(session.readTime),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (session.readWords > 0) {
                                Text(
                                    text = formatWordCount(session.readWords) + "字",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
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
            Text(
                text = "阅读状态由阅读进度自动判定（在读/已读/待看），仅「弃文」可手动覆盖。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
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

// 状态徽章配色
@Composable
private fun statusBadgeColor(status: Int) = when (status) {
    3 -> MaterialTheme.colorScheme.errorContainer
    2 -> MaterialTheme.colorScheme.tertiaryContainer
    1 -> MaterialTheme.colorScheme.primaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun statusBadgeTextColor(status: Int) = when (status) {
    3 -> MaterialTheme.colorScheme.onErrorContainer
    2 -> MaterialTheme.colorScheme.onTertiaryContainer
    1 -> MaterialTheme.colorScheme.onPrimaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatReadTime(millis: Long): String {
    if (millis <= 0) return "0 分钟"
    val hours = millis / 3600000
    val minutes = (millis % 3600000) / 60000
    return when {
        hours > 0 -> "$hours 小时 ${minutes} 分钟"
        minutes > 0 -> "$minutes 分钟"
        else -> "不到 1 分钟"
    }
}

private fun formatWordCount(words: Long): String {
    if (words <= 0) return "0"
    return when {
        words >= 10000 -> "${words / 10000}万"
        words >= 1000 -> "${words / 1000}千"
        else -> "$words"
    }
}

private fun formatDate(millis: Long): String {
    if (millis <= 0) return "—"
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val y = cal.get(java.util.Calendar.YEAR)
    val m = cal.get(java.util.Calendar.MONTH) + 1
    val d = cal.get(java.util.Calendar.DAY_OF_MONTH)
    return "$y-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}
