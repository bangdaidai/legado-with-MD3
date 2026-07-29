package io.legado.app.ui.book.readingmemory.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import io.legado.app.ui.theme.LegadoTheme
import androidx.compose.foundation.background
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.bookmark.BookmarkEditSheet
import java.util.Locale
import io.legado.app.utils.formatReadDuration
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.image.cover.CoilBookCover
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.progressIndicator.AppLinearProgressIndicator
import io.legado.app.ui.widget.components.ReadingSessionTimeline
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingMemoryDetailScreen(
    state: ReadingMemoryDetailUiState,
    onBack: () -> Unit,
    onIntent: (ReadingMemoryDetailIntent) -> Unit,
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    var editingBookmark by remember { mutableStateOf<Bookmark?>(null) }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = state.bookName.ifBlank { "阅读记忆" },
                subtitle = state.author.takeIf { it.isNotBlank() },
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
            )
        },
    ) { paddingValues ->
        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                AppText("加载中…", color = LegadoTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                contentPadding = paddingValues,
                modifier = Modifier.fillMaxSize(),
            ) {
                item { BookInfoSection(state = state, onIntent = onIntent) }
                item { StatsSection(state = state) }
                item { TagsSection(state = state, onIntent = onIntent) }
                item { ProtagonistsSection(state = state, onIntent = onIntent) }
                item { ReviewSection(state = state, onIntent = onIntent) }
                item { IntroSection(state = state, onIntent = onIntent) }
                item { ReadSessionSection(state = state) }
                item { ExcerptSection(state = state, onEditBookmark = { editingBookmark = it }) }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (state.showTagPicker) {
        TagPickerSheet(
            availableTags = state.availableTags,
            selectedTags = state.tags,
            tagColorMap = state.tagColorMap,
            onDismiss = { onIntent(ReadingMemoryDetailIntent.DismissTagPicker) },
            onAddTag = { onIntent(ReadingMemoryDetailIntent.AddTag(it)) },
            onRemoveTag = { onIntent(ReadingMemoryDetailIntent.RemoveTag(it)) },
        )
    }

    if (state.showReviewEditor) {
        ReviewEditorSheet(
            draft = state.reviewDraft,
            onDraftChange = { onIntent(ReadingMemoryDetailIntent.UpdateReviewDraft(it)) },
            onSave = { onIntent(ReadingMemoryDetailIntent.SaveReview) },
            onDismiss = { onIntent(ReadingMemoryDetailIntent.DismissReviewEditor) },
            onDelete = { onIntent(ReadingMemoryDetailIntent.DeleteReview) },
        )
    }

    if (editingBookmark != null) {
        BookmarkEditSheet(
            show = true,
            bookmark = editingBookmark!!,
            onDismiss = { editingBookmark = null },
            onSave = {
                onIntent(ReadingMemoryDetailIntent.EditBookmark(it))
                editingBookmark = null
            },
            onDelete = {
                onIntent(ReadingMemoryDetailIntent.DeleteBookmark(it))
                editingBookmark = null
            },
        )
    }
}

/* ===================== 各区块 ===================== */

@Composable
private fun BookInfoSection(
    state: ReadingMemoryDetailUiState,
    onIntent: (ReadingMemoryDetailIntent) -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CoilBookCover(
                name = state.bookName,
                author = state.author,
                path = state.coverUrl,
                modifier = Modifier
                    .width(64.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp)),
            )
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusText(
                        statusText = state.statusText,
                        abandoned = state.abandoned,
                        onSetAbandoned = { onIntent(ReadingMemoryDetailIntent.SetStatus(it)) },
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    ReadingMemoryRatingBar(
                        rating = state.rating,
                        onRatingChanged = { onIntent(ReadingMemoryDetailIntent.SetRating(it)) },
                    )
                }
                if (state.wordCountText.isNotBlank()) {
                    AppText(
                        text = "字数：${formatWordCount(state.wordCountText)}",
                        fontSize = 13.sp,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppText(
                            text = state.progressInfo,
                            fontSize = 12.sp,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        AppText(
                            text = String.format(Locale.getDefault(), "%.0f%%", state.progress * 100),
                            fontSize = 12.sp,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PlainProgressIndicator(
                        progress = if (state.progress > 0f) state.progress else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusText(
    statusText: String,
    abandoned: Boolean,
    onSetAbandoned: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clickable { expanded = true }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(
                text = statusText.ifBlank { "未开始" },
                fontSize = 14.sp,
                color = LegadoTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = LegadoTheme.colorScheme.primary,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (!abandoned) {
                DropdownMenuItem(
                    text = { AppText("标记为弃文", fontSize = 14.sp) },
                    onClick = {
                        onSetAbandoned(true)
                        expanded = false
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { AppText("取消弃文", fontSize = 14.sp) },
                    onClick = {
                        onSetAbandoned(false)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun StatsSection(state: ReadingMemoryDetailUiState) {
    val stats = state.statistics
    val firstDateText = state.readRecordTimelineDays
        .minByOrNull { it.date }
        ?.date
        ?.let { formatReadDate(it) }
        ?.let { "始于 $it" }
    val lastDateText = if (state.lastReadTime > 0) {
        "上次 ${formatReadDate(state.lastReadTime)}"
    } else null

    SectionCard(title = "阅读数据") {
        // Row 1: 累计时长 / 阅读天数
        StatsRow(
            leftPrimary = formatReadDuration(stats?.totalReadTime ?: state.readRecordTotalTime),
            leftSecondary = firstDateText,
            rightPrimary = "${stats?.readingDays ?: 0}天",
            rightSecondary = lastDateText,
        )
        // Row 2: 阅读进度 / 想法笔记
        val chapterText = if (state.totalChapterCount > 0) {
            "已读${state.durChapterIndex + 1}章 / 共${state.totalChapterCount}章"
        } else if (state.durChapterIndex >= 0) {
            "已读${state.durChapterIndex + 1}章"
        } else null
        StatsRow(
            leftPrimary = String.format(Locale.getDefault(), "%.1f%%", state.progress * 100),
            leftSecondary = chapterText,
            rightPrimary = "想法${state.annotationCount}",
            rightSecondary = "书摘${state.excerptCount}",
        )
        // Row 3: 单日最久 / 阅读字数
        val maxDayText = if (stats != null && stats.maxDayReadTime > 0) {
            formatReadDuration(stats.maxDayReadTime)
        } else "—"
        val maxDayDateText = stats?.maxDayReadDate?.let { formatReadDate(it) }
        val readWordsText = formatWordCountLong(state.totalReadWords).ifBlank { "—" }
        val remainingText = formatWordCountLong(state.remainingWords).ifBlank { "—" }
        StatsRow(
            leftPrimary = maxDayText,
            leftSecondary = if (maxDayDateText != null) "前 ${maxDayDateText}" else null,
            rightPrimary = readWordsText,
            rightSecondary = if (state.remainingWords > 0) "剩余${remainingText}" else null,
        )
    }
}

@Composable
private fun StatsRow(
    leftPrimary: String,
    leftSecondary: String?,
    rightPrimary: String,
    rightSecondary: String?,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
        ) {
            StatCell(
                primary = leftPrimary,
                secondary = leftSecondary,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(16.dp))
            StatCell(
                primary = rightPrimary,
                secondary = rightSecondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatCell(
    primary: String,
    secondary: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = primary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = LegadoTheme.colorScheme.onSurface,
        )
        if (!secondary.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = secondary,
                fontSize = 12.sp,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSection(
    state: ReadingMemoryDetailUiState,
    onIntent: (ReadingMemoryDetailIntent) -> Unit,
) {
    var pendingRemoveTag by remember { mutableStateOf<String?>(null) }
    SectionCard(
        title = "标签",
        trailing = {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "编辑标签",
                tint = LegadoTheme.colorScheme.primary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onIntent(ReadingMemoryDetailIntent.OpenTagPicker) },
            )
        },
    ) {
        if (state.tags.isEmpty()) {
            AppText(
                text = "暂无标签",
                fontSize = 13.sp,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                state.tags.forEach { tag ->
                    TagChip(tag = tag, tagColorMap = state.tagColorMap, onClick = { pendingRemoveTag = tag })
                }
            }
        }
    }
    if (pendingRemoveTag != null) {
        AppAlertDialog(
            show = true,
            title = "移除标签",
            text = "确定要移除标签「${pendingRemoveTag}」吗？",
            onConfirm = {
                onIntent(ReadingMemoryDetailIntent.RemoveTag(pendingRemoveTag!!))
                pendingRemoveTag = null
            },
            onDismissRequest = { pendingRemoveTag = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ProtagonistsSection(
    state: ReadingMemoryDetailUiState,
    onIntent: (ReadingMemoryDetailIntent) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var pendingRemoveProtagonist by remember { mutableStateOf<String?>(null) }

    SectionCard(
        title = "主要人物",
        trailing = {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "添加人物",
                tint = LegadoTheme.colorScheme.primary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable {
                        name = ""
                        adding = true
                    },
            )
        },
    ) {
        if (state.protagonistNames.isEmpty()) {
            AppText(
                text = "暂无人物",
                fontSize = 13.sp,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                state.protagonistNames.forEach { p ->
                    TagChip(
                        tag = p,
                        tagColorMap = state.tagColorMap,
                        onClick = { pendingRemoveProtagonist = p },
                    )
                }
            }
        }
    }

    if (adding) {
        AppModalBottomSheet(
            show = true,
            onDismissRequest = { adding = false },
            title = "添加人物",
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("人物名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))
            FilledTonalButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onIntent(ReadingMemoryDetailIntent.AddProtagonist(name.trim()))
                        adding = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                AppText("确定")
            }
        }
    }

    if (pendingRemoveProtagonist != null) {
        AppAlertDialog(
            show = true,
            title = "移除主角",
            text = "是否移除主角「${pendingRemoveProtagonist}」呀？",
            onConfirm = {
                onIntent(ReadingMemoryDetailIntent.RemoveProtagonist(pendingRemoveProtagonist!!))
                pendingRemoveProtagonist = null
            },
            onDismissRequest = { pendingRemoveProtagonist = null },
        )
    }
}

@Composable
private fun ReviewSection(
    state: ReadingMemoryDetailUiState,
    onIntent: (ReadingMemoryDetailIntent) -> Unit,
) {
    SectionCard(
        title = "我的书评",
        trailing = {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "编辑书评",
                tint = LegadoTheme.colorScheme.primary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onIntent(ReadingMemoryDetailIntent.OpenReviewEditor(state.review)) },
            )
        },
    ) {
        if (state.review.isBlank()) {
            AppText(
                text = "还没有写书评",
                fontSize = 13.sp,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AppText(
                text = state.review,
                fontSize = 14.sp,
                lineHeight = 22.sp,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IntroSection(
    state: ReadingMemoryDetailUiState,
    onIntent: (ReadingMemoryDetailIntent) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var canExpand by remember { mutableStateOf(false) }
    SectionCard(
        title = "作品简介",
        trailing = {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = "编辑简介",
                tint = LegadoTheme.colorScheme.primary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onIntent(ReadingMemoryDetailIntent.OpenBookInfo) },
            )
        },
    ) {
        if (state.intro.isBlank()) {
            AppText(
                text = "暂无简介",
                fontSize = 13.sp,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AppText(
                text = state.intro,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result ->
                    if (!expanded) {
                        val lastLine = (result.lineCount - 1).coerceAtLeast(0)
                        val end = result.getLineEnd(lastLine, visibleEnd = true)
                        canExpand = end < state.intro.length
                    }
                },
                modifier = Modifier.clickable { if (canExpand) expanded = !expanded },
            )
            if (canExpand) {
                Spacer(modifier = Modifier.height(4.dp))
                AppText(
                    text = if (expanded) "收起 ▴" else "展开全文 ▾",
                    fontSize = 12.sp,
                    color = LegadoTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }
    }
}

@Composable
private fun ExcerptSection(
    state: ReadingMemoryDetailUiState,
    onEditBookmark: (Bookmark) -> Unit,
) {
    if (state.excerpts.isEmpty()) return
    SectionCard(title = "阅读摘录") {
        state.excerpts.forEachIndexed { index, excerpt ->
            Column(modifier = Modifier.clickable { onEditBookmark(excerpt) }) {
                if (index > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = LegadoTheme.colorScheme.outlineVariant,
                    )
                }
                AppText(
                    text = excerpt.chapterName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                if (excerpt.content.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    AppText(text = excerpt.content, fontSize = 14.sp, lineHeight = 22.sp)
                }
                if (!excerpt.bookText.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    AppText(
                        text = excerpt.bookText,
                        fontSize = 13.sp,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/* ===================== 弹窗 ===================== */

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TagPickerSheet(
    availableTags: List<String>,
    selectedTags: List<String>,
    tagColorMap: Map<String, Long> = emptyMap(),
    onDismiss: () -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
) {
    var customTag by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    AppModalBottomSheet(
        show = true,
        onDismissRequest = onDismiss,
        title = "选择标签",
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索标签") },
            singleLine = true,
            leadingIcon = {
                Icon(imageVector = Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selectedTags.isNotEmpty()) {
                item {
                    AppText(
                        text = "已选标签",
                        fontSize = 13.sp,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        selectedTags.forEach { tag ->
                            TagChip(tag = tag, tagColorMap = tagColorMap, onRemove = { onRemoveTag(tag) })
                        }
                    }
                }
            }

            val candidates = availableTags
                .filter { it !in selectedTags }
                .filter { it.contains(query, ignoreCase = true) }
            if (candidates.isNotEmpty()) {
                item {
                    AppText(
                        text = "可选标签",
                        fontSize = 13.sp,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(candidates) { tag ->
                    TagChip(tag = tag, tagColorMap = tagColorMap, onClick = { onAddTag(tag) })
                }
            } else if (selectedTags.isEmpty()) {
                item {
                    AppText(
                        text = "暂无可选标签，可在下方添加",
                        fontSize = 13.sp,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = customTag,
            onValueChange = { customTag = it },
            label = { Text("自定义标签") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(
                onClick = {
                    if (customTag.isNotBlank()) {
                        onAddTag(customTag.trim())
                        customTag = ""
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                AppText("添加")
            }
            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            ) {
                AppText("完成")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewEditorSheet(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    AppModalBottomSheet(
        show = true,
        onDismissRequest = onDismiss,
        title = "编辑书评",
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = { Text("书评内容") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (onDelete != null && draft.isNotBlank()) {
            FilledTonalButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                AppText("删除书评")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        FilledTonalButton(
            onClick = onSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AppText("保存")
        }
    }
}

/* ===================== 通用组件 ===================== */

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                trailing()
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun TagChip(
    tag: String,
    tagColorMap: Map<String, Long> = emptyMap(),
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val clickModifier = if (onClick != null || onRemove != null) {
        Modifier.clickable { (onClick ?: onRemove)?.invoke() }
    } else Modifier

    val tagColor = tagColorMap[tag]
    val backgroundColor = if (tagColor != null) {
        Color(tagColor).copy(alpha = 0.14f)
    } else {
        LegadoTheme.colorScheme.surfaceContainer
    }
    val contentColor = if (tagColor != null) {
        Color(tagColor)
    } else {
        LegadoTheme.colorScheme.onSurfaceVariant
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .then(clickModifier),
    ) {
        Text(
            text = tag,
            style = LegadoTheme.typography.labelMedium,
            color = contentColor,
        )
        if (onRemove != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "移除",
                modifier = Modifier
                    .size(14.dp)
                    .clickable { onRemove.invoke() },
                tint = contentColor,
            )
        }
    }
}

private fun formatWordCount(raw: String): String {
    if (raw.isBlank()) return ""
    if (raw.contains("万")) return raw
    val digits = raw.filter { it.isDigit() }
    val count = digits.toLongOrNull() ?: return raw
    return when {
        count >= 10000 -> {
            val wan = count.toFloat() / 10000
            if (wan % 1 == 0f) "${wan.toLong()}万字" else String.format("%.1f万字", wan)
        }
        else -> "${count}字"
    }
}

private fun formatWordCountLong(count: Long): String {
    return when {
        count >= 10000 -> {
            val wan = count.toFloat() / 10000
            if (wan % 1 == 0f) "${wan.toLong()}万字" else String.format("%.1f万字", wan)
        }
        count > 0 -> "${count}字"
        else -> ""
    }
}

@Composable
private fun ReadSessionSection(state: ReadingMemoryDetailUiState) {
    if (state.readRecordTimelineDays.isEmpty()) return
    SectionCard(title = "阅读会话") {
        GlassCard(
            containerColor = LegadoTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
            cornerRadius = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = LegadoTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                AppText(
                    text = "总阅读时长",
                    fontSize = 12.sp,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                AppText(
                    text = formatReadDuration(state.readRecordTotalTime),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        ReadingSessionTimeline(
            timelineDays = state.readRecordTimelineDays,
            showChapterInfo = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatReadDate(input: Any): String {
    val date = when (input) {
        is Long -> Date(input)
        is String -> try {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(input)
        } catch (_: Exception) {
            null
        }
        else -> null
    } ?: return ""
    return SimpleDateFormat("yyyy年M月d日", Locale.getDefault()).format(date)
}

@Composable
private fun PlainProgressIndicator(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    val scheme = LegadoTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(scheme.surfaceVariant),
    ) {
        if (progress != null) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(scheme.primary),
            )
        }
    }
}
