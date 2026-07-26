package io.legado.app.ui.book.readingmemory.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.repository.ReadingStatistics
import io.legado.app.constant.AppLog
import io.legado.app.ui.book.info.HighlightedTag
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.progressIndicator.AppLinearProgressIndicator
import io.legado.app.ui.widget.components.AppPullToRefresh
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.card.HighlightTagRow
import io.legado.app.ui.widget.components.image.cover.CoilBookCover
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReadingMemoryDetailScreen(
    state: ReadingMemoryDetailUiState,
    onBack: () -> Unit,
    onIntent: (ReadingMemoryDetailIntent) -> Unit,
) {
    val scrollState = rememberScrollState()
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    AppLog.put("[阅读记忆] DetailScreen 渲染 loading=${state.loading} bookName=${state.bookName} 进度=${state.progressInfo}")

    Scaffold(
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = state.bookName.ifBlank { "阅读记忆" },
                subtitle = state.author.ifBlank { null },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { onIntent(ReadingMemoryDetailIntent.Refresh) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "同步")
                    }
                },
                bottomContent = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        CoilBookCover(
                            name = state.bookName.ifBlank { null },
                            author = state.author.ifBlank { null },
                            path = state.coverUrl?.takeIf { it.isNotBlank() },
                            radius = 8.dp,
                            modifier = Modifier
                                .width(92.dp)
                                .height(130.dp)
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            AppText(
                                state.bookName.ifBlank { "未知书名" },
                                style = LegadoTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (state.author.isNotBlank()) {
                                AppText(
                                    state.author,
                                    style = LegadoTheme.typography.bodyMedium,
                                    color = LegadoTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                shape = LegadoTheme.shapes.small,
                                color = statusBadgeColor(state.status),
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                AppText(
                                    state.statusText,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                    style = LegadoTheme.typography.labelMedium,
                                    color = statusBadgeTextColor(state.status)
                                )
                            }
                            ReadingMemoryRatingBar(
                                rating = state.rating.toFloat(),
                                onRatingChanged = { onIntent(ReadingMemoryDetailIntent.SetRating(it)) },
                                enabled = state.isStillOnShelf,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        AppPullToRefresh(
            isRefreshing = state.loading,
            onRefresh = { onIntent(ReadingMemoryDetailIntent.Refresh) },
            scrollBehavior = scrollBehavior,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (state.bookName.isBlank()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 字数 + 进度条
                    BasicInfoSection(state = state)

                    // 简介
                    IntroSection(
                        intro = state.intro,
                        onEdit = { onIntent(ReadingMemoryDetailIntent.EditIntro(it)) }
                    )

                    // 书籍标签
                    if (state.tags.isNotEmpty()) {
                        TagsSection(state.tags)
                    }

                    // 主角（可增删）
                    ProtagonistsSection(
                        names = state.protagonistNames,
                        onAdd = { onIntent(ReadingMemoryDetailIntent.AddProtagonist(it)) },
                        onRemove = { onIntent(ReadingMemoryDetailIntent.RemoveProtagonist(it)) }
                    )

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
        }
    }

    // 评价编辑弹层
    if (state.showReviewEditor) {
        AppModalBottomSheet(
            show = state.showReviewEditor,
            onDismissRequest = { onIntent(ReadingMemoryDetailIntent.DismissReviewEditor) },
            title = "编辑书评"
        ) {
            OutlinedTextField(
                value = state.reviewDraft,
                onValueChange = { onIntent(ReadingMemoryDetailIntent.UpdateReviewDraft(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                maxLines = 10,
                placeholder = { AppText("写写你对这本书的感受...") }
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = { onIntent(ReadingMemoryDetailIntent.DismissReviewEditor) },
                    modifier = Modifier.weight(1f)
                ) {
                    AppText("取消")
                }
                TextButton(
                    onClick = { onIntent(ReadingMemoryDetailIntent.SaveReview) },
                    modifier = Modifier.weight(1f)
                ) {
                    AppText("保存")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // 弃文确认弹窗
    AppAlertDialog(
        show = state.showAbandonedDialog,
        onDismissRequest = { onIntent(ReadingMemoryDetailIntent.DismissAbandonedDialog) },
        title = "确认弃文",
        text = "确定要将这本书标记为弃文吗？阅读记录仍会保留。",
        confirmText = "确认弃文",
        onConfirm = { onIntent(ReadingMemoryDetailIntent.ConfirmAbandoned) },
        dismissText = "取消",
        onDismiss = { onIntent(ReadingMemoryDetailIntent.DismissAbandonedDialog) }
    )
}

@Composable
private fun BasicInfoSection(state: ReadingMemoryDetailUiState) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (state.wordCountText.isNotBlank()) {
                AppText(
                    state.wordCountText,
                    style = LegadoTheme.typography.bodyMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant
                )
            } else if (state.wordCount > 0) {
                AppText(
                    formatWordCount(state.wordCount) + "字",
                    style = LegadoTheme.typography.bodyMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

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
                AppText(
                    text = "${(state.progress.coerceIn(0f, 1f) * 100).toInt()}%",
                    style = LegadoTheme.typography.labelMedium,
                    color = LegadoTheme.colorScheme.primary
                )
            }
            if (state.progressInfo.isNotBlank()) {
                AppText(
                    text = state.progressInfo,
                    style = LegadoTheme.typography.labelSmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun IntroSection(intro: String, onEdit: (String) -> Unit) {
    if (intro.isNotBlank()) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText(
                        text = "内容简介",
                        style = LegadoTheme.typography.titleSmall,
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
                AppText(
                    text = intro,
                    style = LegadoTheme.typography.bodyMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TagsSection(tags: List<String>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            AppText(
                text = "书籍标签",
                style = LegadoTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            HighlightTagRow(
                tags = listOf(HighlightedTag(title = null, matchedLabels = tags))
            )
        }
    }
}

@Composable
private fun ProtagonistsSection(
    names: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            AppText(
                text = "主角",
                style = LegadoTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 添加主角
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { AppText("输入主角名") },
                    singleLine = true
                )
                IconButton(
                    onClick = {
                        val name = input.trim()
                        if (name.isNotBlank()) {
                            onAdd(name)
                            input = ""
                        }
                    }
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加主角")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (names.isEmpty()) {
                AppText(
                    text = "暂无主角，可手动添加，或在阅读页长按文字标记。",
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    names.forEach { name ->
                        Surface(
                            shape = LegadoTheme.shapes.small,
                            color = LegadoTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp)
                            ) {
                                AppText(
                                    text = name,
                                    style = LegadoTheme.typography.labelLarge,
                                    color = LegadoTheme.colorScheme.onSecondaryContainer
                                )
                                IconButton(
                                    onClick = { onRemove(name) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "删除主角",
                                        modifier = Modifier.size(14.dp)
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
private fun StatsSection(
    stats: ReadingStatistics,
    progress: Float,
    progressInfo: String,
    annotationCount: Int,
    lastReadTime: Long,
) {
    val days = if (stats.readingDays > 0) stats.readingDays else 1
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            AppText(
                text = "阅读数据",
                style = LegadoTheme.typography.titleSmall,
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
        AppText(
            text = primary,
            style = LegadoTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = LegadoTheme.colorScheme.primary
        )
        AppText(
            text = secondary,
            style = LegadoTheme.typography.labelSmall,
            color = LegadoTheme.colorScheme.onSurfaceVariant
        )
        AppText(
            text = label,
            style = LegadoTheme.typography.labelSmall,
            color = LegadoTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExcerptsSection(excerpts: List<ReadingMemoryExcerpt>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            AppText(
                text = "书摘（含笔记）",
                style = LegadoTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (excerpts.isEmpty()) {
                EmptyMessage("暂无含笔记的书签。在正文长按添加带笔记的书签后，这里会自动汇总。")
            } else {
                excerpts.forEachIndexed { index, excerpt ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = LegadoTheme.colorScheme.outlineVariant
                        )
                    }
                    if (excerpt.chapterName.isNotBlank()) {
                        AppText(
                            text = excerpt.chapterName,
                            style = LegadoTheme.typography.labelMedium,
                            color = LegadoTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (excerpt.note.isNotBlank()) {
                        AppText(
                            text = excerpt.note,
                            style = LegadoTheme.typography.bodyMedium,
                            color = LegadoTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (!excerpt.originText.isNullOrBlank()) {
                        AppText(
                            text = excerpt.originText,
                            style = LegadoTheme.typography.bodySmall,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewSection(review: String, onEdit: () -> Unit) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppText(
                    text = "书评",
                    style = LegadoTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = onEdit) {
                    AppText(if (review.isBlank()) "写书评" else "编辑")
                }
            }
            if (review.isNotBlank()) {
                AppText(
                    text = review,
                    style = LegadoTheme.typography.bodyMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant
                )
            } else {
                AppText(
                    text = "暂无书评，点击上方按钮添加",
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun ReadingSessionSection(sessions: List<ReadingSessionItem>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            AppText(
                text = "阅读会话",
                style = LegadoTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (sessions.isEmpty()) {
                EmptyMessage("暂无阅读会话记录。开始阅读后，按天的阅读时长会自动汇总到这里。")
            } else {
                sessions.forEachIndexed { index, session ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = LegadoTheme.colorScheme.outlineVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppText(
                            text = session.date.ifBlank { "未知日期" },
                            style = LegadoTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            AppText(
                                text = formatReadTime(session.readTime),
                                style = LegadoTheme.typography.bodyMedium,
                                color = LegadoTheme.colorScheme.primary
                            )
                            if (session.readWords > 0) {
                                AppText(
                                    text = formatWordCount(session.readWords) + "字",
                                    style = LegadoTheme.typography.bodySmall,
                                    color = LegadoTheme.colorScheme.onSurfaceVariant
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
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            AppText(
                text = "阅读状态",
                style = LegadoTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            AppText(
                text = "阅读状态由阅读进度自动判定（在读/已读/待看），仅「弃文」可手动覆盖。",
                style = LegadoTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (abandoned) {
                Button(
                    onClick = { onToggle(false) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LegadoTheme.colorScheme.tertiary
                    )
                ) {
                    AppText("取消弃文标记")
                }
            } else {
                OutlinedButton(
                    onClick = { onToggle(true) },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = LegadoTheme.colorScheme.error
                    )
                ) {
                    AppText("标记为弃文")
                }
            }
        }
    }
}

// 状态徽章配色
@Composable
private fun statusBadgeColor(status: Int) = when (status) {
    3 -> LegadoTheme.colorScheme.errorContainer
    2 -> LegadoTheme.colorScheme.tertiaryContainer
    1 -> LegadoTheme.colorScheme.primaryContainer
    else -> LegadoTheme.colorScheme.surfaceVariant
}

@Composable
private fun statusBadgeTextColor(status: Int) = when (status) {
    3 -> LegadoTheme.colorScheme.onErrorContainer
    2 -> LegadoTheme.colorScheme.onTertiaryContainer
    1 -> LegadoTheme.colorScheme.onPrimaryContainer
    else -> LegadoTheme.colorScheme.onSurfaceVariant
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
