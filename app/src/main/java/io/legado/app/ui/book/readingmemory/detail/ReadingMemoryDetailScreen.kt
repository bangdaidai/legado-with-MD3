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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.LocalAppUiConfiguration
import io.legado.app.ui.widget.components.card.TicketNotchDivider
import io.legado.app.ui.book.tagmanage.TagEditData
import io.legado.app.ui.book.tagmanage.TagEditSheet
import androidx.compose.foundation.background
import io.legado.app.data.entities.BookMarking
import io.legado.app.domain.model.TextProcessAnchor
import io.legado.app.ui.book.read.MarkingUiState
import io.legado.app.ui.book.read.sheet.MarkingSheet
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.icon.AppIcons
import java.util.Locale
import io.legado.app.ui.widget.shareCard.ShareCardPreviewSheet
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.card.TagChip
import io.legado.app.ui.widget.components.card.TagChipSize
import io.legado.app.ui.widget.components.image.cover.CoilBookCover
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.progressIndicator.AppLinearProgressIndicator
import io.legado.app.ui.widget.components.ReadingSessionTimeline
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingMemoryDetailScreen(
    state: ReadingMemoryDetailUiState,
    onBack: () -> Unit,
    onIntent: (ReadingMemoryDetailIntent) -> Unit,
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    var editingMarking by remember { mutableStateOf<BookMarking?>(null) }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = state.bookName.ifBlank { "阅读记忆" },
                subtitle = state.author.takeIf { it.isNotBlank() },
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                actions = {
                    TopBarActionButton(
                        onClick = { onIntent(ReadingMemoryDetailIntent.GenerateShareCard) },
                        imageVector = Icons.Default.Image,
                        contentDescription = "生成分享卡片",
                    )
                },
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
                item { ProtagonistsSection(state = state, onIntent = onIntent) }
                item { IntroSection(state = state, onIntent = onIntent) }
                item { TagsSection(state = state, onIntent = onIntent) }
                item { StatsSection(state = state) }
                item { ExcerptSection(state = state, onEditMarking = { editingMarking = it }) }
                item { ReviewSection(state = state, onIntent = onIntent) }
                item { ReadSessionSection(state = state) }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    val tagEditData = remember(state.showTagPicker) {
        mutableStateOf<TagEditData?>(if (state.showTagPicker) TagEditData() else null)
    }

    TagEditSheet(
        data = tagEditData.value,
        groups = state.tagGroups,
        onChange = { tagEditData.value = it },
        onConfirm = {
            val trimmed = it.name.trim()
            if (trimmed.isNotBlank()) {
                onIntent(ReadingMemoryDetailIntent.AddTag(trimmed))
            }
            onIntent(ReadingMemoryDetailIntent.DismissTagPicker)
        },
        onPickColor = {},
        onDismiss = { onIntent(ReadingMemoryDetailIntent.DismissTagPicker) },
    )

    if (state.showReviewEditor) {
        ReviewEditorSheet(
            draft = state.reviewDraft,
            onDraftChange = { onIntent(ReadingMemoryDetailIntent.UpdateReviewDraft(it)) },
            onSave = { onIntent(ReadingMemoryDetailIntent.SaveReview) },
            onDismiss = { onIntent(ReadingMemoryDetailIntent.DismissReviewEditor) },
            onDelete = { onIntent(ReadingMemoryDetailIntent.DeleteReview) },
            onGenerateShareCard = { onIntent(ReadingMemoryDetailIntent.GenerateShareCardFromReview) },
        )
    }

    editingMarking?.let { marking ->
        MarkingSheet(
            show = true,
            state = MarkingUiState(editing = marking),
            onDismissRequest = { editingMarking = null },
            onSave = { _, note ->
                onIntent(ReadingMemoryDetailIntent.EditMarking(marking.copy(note = note)))
                editingMarking = null
            },
            onDelete = {
                onIntent(ReadingMemoryDetailIntent.DeleteMarking(marking.id))
                editingMarking = null
            },
            showStyleConfig = false,
            onGenerateShareCard = {
                editingMarking = null
                onIntent(ReadingMemoryDetailIntent.GenerateShareCardFromMarking(marking))
            },
        )
    }

    ShareCardPreviewSheet(
        show = state.showShareCard,
        data = state.shareCardData,
        loading = state.shareCardLoading,
        onDismissRequest = { onIntent(ReadingMemoryDetailIntent.DismissShareCard) },
    )
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
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onIntent(ReadingMemoryDetailIntent.OpenBook) },
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
                        style = LegadoTheme.typography.labelMedium,
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
                            style = LegadoTheme.typography.bodySmall,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        AppText(
                            text = String.format(Locale.getDefault(), "%.0f%%", state.progress * 100),
                            style = LegadoTheme.typography.bodySmall,
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
                style = LegadoTheme.typography.labelLarge,
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
                    text = { AppText("标记为弃文", style = LegadoTheme.typography.labelLarge) },
                    onClick = {
                        onSetAbandoned(true)
                        expanded = false
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { AppText("取消弃文", style = LegadoTheme.typography.labelLarge) },
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
    val scheme = LegadoTheme.colorScheme

    val firstDateText = state.firstReadDate?.let { formatReadDate(it) }?.let { "始于$it" }
        ?: state.readRecordTimelineDays.minByOrNull { it.date }?.date?.let { formatReadDate(it) }?.let { "始于$it" }
    val lastReadText = formatLastReadRelative(state.lastReadTime)

    val totalDuration = formatDurationNoSeconds(stats?.totalReadTime ?: state.readRecordTotalTime)
    val readingDays = "${stats?.readingDays ?: 0}天"
    val maxDayDuration = if (stats != null && stats.maxDayReadTime > 0) {
        formatDurationNoSeconds(stats.maxDayReadTime)
    } else "—"
    val maxDayDate = stats?.maxDayReadDate?.let { formatReadDate(it) }
    val totalWords = formatWordCountLong(state.totalReadWords).ifBlank { "—" }
    val remainingText = if (state.remainingWords > 0) "剩余${formatWordCountLong(state.remainingWords)}" else null

    SectionCard(title = "阅读数据") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatItem(title = "累计时长", primary = totalDuration, secondary = firstDateText, primaryColor = scheme.primary, modifier = Modifier.weight(1f))
                StatItem(title = "阅读天数", primary = readingDays, secondary = lastReadText, primaryColor = scheme.primary, modifier = Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatItem(title = "单日阅读最久", primary = maxDayDuration, secondary = maxDayDate, primaryColor = scheme.primary, modifier = Modifier.weight(1f))
                StatItem(title = "阅读总字数", primary = totalWords, secondary = remainingText, primaryColor = scheme.primary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatItem(
    title: String,
    primary: String,
    secondary: String?,
    primaryColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            style = LegadoTheme.typography.labelMediumEmphasized,
            color = LegadoTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = buildStyledStatText(primary),
            style = LegadoTheme.typography.headlineSmall,
            color = primaryColor,
        )
        if (!secondary.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = secondary,
                style = LegadoTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 将数值+单位字符串转为带大小层次的 AnnotatedString。
 * 数字部分保持原字号，单位部分（小时/分/天/万字）缩小显示。
 */
private fun buildStyledStatText(text: String): AnnotatedString {
    if (text == "—") return AnnotatedString(text)
    return buildAnnotatedString {
        val unitPattern = Regex("(\\d+\\.?\\d*)")
        var lastEnd = 0
        unitPattern.findAll(text).forEach { match ->
            // 数字之前的文字（单位文字）
            if (match.range.first > lastEnd) {
                withStyle(SpanStyle(fontSize = 0.7.em)) {
                    append(text.substring(lastEnd, match.range.first))
                }
            }
            // 数字部分保持原大小
            append(match.value)
            lastEnd = match.range.last + 1
        }
        // 末尾剩余的单位文字
        if (lastEnd < text.length) {
            withStyle(SpanStyle(fontSize = 0.7.em)) {
                append(text.substring(lastEnd))
            }
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
                    .clickable { onIntent(ReadingMemoryDetailIntent.OpenTagEdit) },
            )
        },
    ) {
        if (state.tags.isEmpty()) {
            AppText(
                text = "暂无标签",
                style = LegadoTheme.typography.labelMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                state.tags.forEach { tag ->
                    TagChip(
                        tag = tag,
                        color = state.tagColorMap[tag],
                        showColoredBorder = state.bookshelfTagBorder,
                        onClick = { pendingRemoveTag = tag },
                    )
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
                style = LegadoTheme.typography.labelMedium,
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
                        showColoredBorder = state.bookshelfTagBorder,
                        onClick = { pendingRemoveProtagonist = p },
                    )
                }
            }
        }
    }

    if (adding) {
        AppAlertDialog(
            show = true,
            onDismissRequest = { adding = false },
            title = "添加人物",
            content = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("人物名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmText = "确定",
            onConfirm = {
                if (name.isNotBlank()) {
                    onIntent(ReadingMemoryDetailIntent.AddProtagonist(name.trim()))
                    adding = false
                }
            },
            dismissText = "取消",
            onDismiss = { adding = false },
        )
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
        onClick = { onIntent(ReadingMemoryDetailIntent.OpenReviewEditor(state.review)) },
    ) {
        if (state.review.isBlank()) {
            AppText(
                text = "还没有写书评",
                style = LegadoTheme.typography.labelMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AppText(
                text = state.review,
                style = LegadoTheme.typography.labelLarge,
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
                    .clickable { onIntent(ReadingMemoryDetailIntent.OpenBookInfoEdit) },
            )
        },
    ) {
        if (state.intro.isBlank()) {
            AppText(
                text = "暂无简介",
                style = LegadoTheme.typography.labelMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AppText(
                text = state.intro,
                style = LegadoTheme.typography.labelLarge,
                fontWeight = FontWeight.Normal,
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
                    style = LegadoTheme.typography.bodySmall,
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
    onEditMarking: (BookMarking) -> Unit,
) {
    if (state.excerpts.isEmpty()) return
    val ticketThemeSettings = LocalAppUiConfiguration.current.theme
    val ticketBorderColor = (if (LegadoTheme.isDark) {
        ticketThemeSettings.baseCardBorderColorNight
    } else {
        ticketThemeSettings.baseCardBorderColor
    }).takeIf { it != 0 }?.let(::Color) ?: LegadoTheme.colorScheme.outlineVariant
    val ticketStrokeWidth = ticketThemeSettings.baseCardBorderWidth.dp
    SectionCard(title = "书摘笔记") {
        state.excerpts.forEachIndexed { index, excerpt ->
            val selectedText = remember(excerpt.anchorJson) {
                GSON.fromJsonObject<TextProcessAnchor>(excerpt.anchorJson)
                    .getOrNull()?.selectedText.orEmpty()
            }
            Column(modifier = Modifier.clickable { onEditMarking(excerpt) }) {
                if (index > 0) {
                    TicketNotchDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        notchRadius = 0.dp,
                        color = ticketBorderColor,
                        strokeWidth = ticketStrokeWidth,
                    )
                }
                AppText(
                    text = excerpt.chapterName,
                    style = LegadoTheme.typography.labelMediumEmphasized,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                if (excerpt.note.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    AppText(
                        text = excerpt.note,
                        style = LegadoTheme.typography.labelLarge,
                        fontWeight = FontWeight.Normal,
                        color = LegadoTheme.colorScheme.primary,
                        lineHeight = 22.sp,
                    )
                }
                if (selectedText.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    AppText(
                        text = selectedText,
                        style = LegadoTheme.typography.labelLarge,
                        fontWeight = FontWeight.Normal,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/* ===================== 弹窗 ===================== */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewEditorSheet(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onGenerateShareCard: (() -> Unit)? = null,
) {
    AppModalBottomSheet(
        show = true,
        onDismissRequest = onDismiss,
        title = "编辑书评",
        startAction = if (onDelete != null && draft.isNotBlank()) {
            {
                MediumTonalButton(
                    onClick = onDelete,
                    icon = AppIcons.Delete,
                    contentDescription = "删除书评",
                )
            }
        } else null,
        endAction = {
            MediumTonalButton(
                onClick = onSave,
                icon = AppIcons.Check,
                contentDescription = "保存",
            )
        },
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = { Text("书评内容") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        if (onGenerateShareCard != null) {
            Spacer(modifier = Modifier.height(12.dp))
            FilledTonalButton(
                onClick = onGenerateShareCard,
                modifier = Modifier.fillMaxWidth(),
            ) {
                AppText("生成书评票")
            }
        }
    }
}

/* ===================== 通用组件 ===================== */

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText(
                    text = title,
                    style = LegadoTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                trailing()
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
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
    if (count <= 0) return ""
    val wan = count.toFloat() / 10000
    return if (wan % 1 == 0f) "${wan.toLong()}万字" else String.format("%.1f万字", wan)
}

@Composable
private fun ReadSessionSection(state: ReadingMemoryDetailUiState) {
    if (state.readRecordTimelineDays.isEmpty()) return
    SectionCard(title = "阅读会话") {
        ReadingSessionTimeline(
            timelineDays = state.readRecordTimelineDays,
            showChapterInfo = true,
            modifier = Modifier.fillMaxWidth(),
            parentIsScrollable = true,
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

private fun formatDurationNoSeconds(millis: Long): String {
    if (millis <= 0) return "—"
    val hours = millis / (1000 * 60 * 60)
    val minutes = millis % (1000 * 60 * 60) / (1000 * 60)
    return when {
        hours > 0 && minutes > 0 -> "${hours}小时${minutes}分"
        hours > 0 -> "${hours}小时"
        minutes > 0 -> "${minutes}分"
        else -> "0分"
    }
}

private fun formatLastReadRelative(millis: Long): String {
    if (millis <= 0) return ""
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val yesterday = today.clone() as Calendar
    yesterday.add(Calendar.DAY_OF_YEAR, -1)
    val readDay = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return when {
        readDay == today -> "上次阅读是今天"
        readDay == yesterday -> "上次阅读是昨天"
        else -> "上次 ${formatReadDate(millis)}"
    }
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
