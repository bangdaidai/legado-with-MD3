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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.ui.theme.LegadoTheme
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
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingMemoryDetailScreen(
    state: ReadingMemoryDetailUiState,
    onBack: () -> Unit,
    onIntent: (ReadingMemoryDetailIntent) -> Unit,
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

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
                item { ExcerptSection(state = state) }
                item { Spacer(modifier = Modifier.height(24.dp)) }
            }
        }
    }

    if (state.showTagPicker) {
        TagPickerSheet(
            availableTags = state.availableTags,
            selectedTags = state.tags,
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
                    StatusDropdown(
                        statusText = state.statusText,
                        abandoned = state.abandoned,
                        onSetAbandoned = { onIntent(ReadingMemoryDetailIntent.SetStatus(it)) },
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    ReadingMemoryRatingBar(
                        rating = state.rating,
                        onRatingChanged = { onIntent(ReadingMemoryDetailIntent.SetRating(it)) },
                        modifier = Modifier.scale(0.75f),
                    )
                }
                if (state.wordCountText.isNotBlank()) {
                    AppText(
                        text = "字数：${state.wordCountText}",
                        fontSize = 13.sp,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppLinearProgressIndicator(
                        progress = if (state.progress > 0f) state.progress else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AppText(
                        text = "${state.progressInfo} · ${String.format(Locale.getDefault(), "%.0f%%", state.progress * 100)}",
                        fontSize = 12.sp,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDropdown(
    statusText: String,
    abandoned: Boolean,
    onSetAbandoned: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            AppText(text = statusText.ifBlank { "未开始" }, fontSize = 13.sp)
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
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
    SectionCard(title = "阅读数据") {
        val firstDateText = state.readRecordTimelineDays
            .minByOrNull { it.date }
            ?.date
            ?.let { formatReadDate(it) }
            ?.let { "始于 $it" }
        val lastDateText = if (state.lastReadTime > 0) {
            "上次 ${formatReadDate(state.lastReadTime)}"
        } else null
        Row(modifier = Modifier.fillMaxWidth()) {
            StatBlock(
                title = "累计时长",
                primary = formatReadDuration(state.readRecordTotalTime),
                secondary = firstDateText,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            StatBlock(
                title = "阅读天数",
                primary = "${state.statistics?.readingDays ?: 0}天",
                secondary = lastDateText,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            StatBlock(
                title = "全书字数",
                primary = state.wordCountText.ifBlank { "—" },
                secondary = null,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            StatBlock(
                title = "笔记数",
                primary = state.annotationCount.toString(),
                secondary = null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatBlock(
    title: String,
    primary: String,
    secondary: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        AppText(
            text = title,
            fontSize = 12.sp,
            color = LegadoTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppText(
                text = primary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            if (secondary != null) {
                Spacer(modifier = Modifier.width(6.dp))
                AppText(
                    text = secondary,
                    fontSize = 11.sp,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
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
    SectionCard(title = "标签") {
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
                    TagChip(
                        tag = tag,
                        onRemove = { onIntent(ReadingMemoryDetailIntent.RemoveTag(tag)) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        FilledTonalButton(
            onClick = { onIntent(ReadingMemoryDetailIntent.OpenTagPicker) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            AppText("添加标签")
        }
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

    SectionCard(title = "主要人物") {
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
                        onRemove = { onIntent(ReadingMemoryDetailIntent.RemoveProtagonist(p)) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        FilledTonalButton(
            onClick = {
                name = ""
                adding = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            AppText("添加人物")
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
}

@Composable
private fun ReviewSection(
    state: ReadingMemoryDetailUiState,
    onIntent: (ReadingMemoryDetailIntent) -> Unit,
) {
    SectionCard(title = "我的书评") {
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
        Spacer(modifier = Modifier.height(12.dp))
        FilledTonalButton(
            onClick = { onIntent(ReadingMemoryDetailIntent.OpenReviewEditor(state.review)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            AppText(if (state.review.isBlank()) "写书评" else "编辑书评")
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
    SectionCard(title = "作品简介") {
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
                onTextLayout = { canExpand = it.lineCount > 5 },
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
        Spacer(modifier = Modifier.height(12.dp))
        FilledTonalButton(
            onClick = { onIntent(ReadingMemoryDetailIntent.OpenBookInfo) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            AppText("编辑简介")
        }
    }
}

@Composable
private fun ExcerptSection(state: ReadingMemoryDetailUiState) {
    if (state.excerpts.isEmpty()) return
    SectionCard(title = "阅读摘录") {
        state.excerpts.forEachIndexed { index, excerpt ->
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
            if (excerpt.note.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                AppText(text = excerpt.note, fontSize = 14.sp, lineHeight = 22.sp)
            }
            if (!excerpt.originText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                AppText(
                    text = excerpt.originText,
                    fontSize = 13.sp,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
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
    onDismiss: () -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
) {
    var customTag by remember { mutableStateOf("") }

    AppModalBottomSheet(
        show = true,
        onDismissRequest = onDismiss,
        title = "选择标签",
    ) {
        if (selectedTags.isNotEmpty()) {
            AppText(
                text = "已选标签",
                fontSize = 13.sp,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                selectedTags.forEach { tag ->
                    TagChip(tag = tag, onRemove = { onRemoveTag(tag) })
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        val candidates = availableTags.filter { it !in selectedTags }
        if (candidates.isNotEmpty()) {
            AppText(
                text = "推荐标签",
                fontSize = 13.sp,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                candidates.forEach { tag ->
                    TagChip(tag = tag, onAdd = { onAddTag(tag) })
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = customTag,
            onValueChange = { customTag = it },
            label = { Text("自定义标签") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        FilledTonalButton(
            onClick = {
                if (customTag.isNotBlank()) {
                    onAddTag(customTag.trim())
                    customTag = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            AppText("添加")
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
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            AppText(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun TagChip(
    tag: String,
    onRemove: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
) {
    NormalCard(
        onClick = onAdd ?: onRemove,
        cornerRadius = 16.dp,
        containerColor = tagColor(tag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(text = tag, fontSize = 13.sp, color = Color.White)
            if (onRemove != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "移除",
                    modifier = Modifier.size(14.dp),
                    tint = Color.White,
                )
            } else if (onAdd != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "添加",
                    modifier = Modifier.size(14.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

private fun tagColor(tag: String): Color {
    val palette = listOf(
        Color(0xFFE57373), Color(0xFFBA68C8), Color(0xFF64B5F6), Color(0xFF4DB6AC),
        Color(0xFF81C784), Color(0xFFFFB74D), Color(0xFFA1887F), Color(0xFF90A4AE),
        Color(0xFFF06292), Color(0xFF7986CB),
    )
    val h = if (tag.hashCode() == Int.MIN_VALUE) 0 else kotlin.math.abs(tag.hashCode())
    return palette[h % palette.size].copy(alpha = 0.85f)
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
