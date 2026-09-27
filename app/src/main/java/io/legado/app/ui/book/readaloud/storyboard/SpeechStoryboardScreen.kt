package io.legado.app.ui.book.readaloud.storyboard

import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarScrollBehavior
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeechStoryboardScreen(
    state: SpeechStoryboardUiState,
    onIntent: (SpeechStoryboardIntent) -> Unit,
    effects: Flow<SpeechStoryboardEffect>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val inChapterDetail = state.selectedChapterIndex != null
    val latestOnIntent by rememberUpdatedState(onIntent)
    val previewFailed = stringResource(R.string.voice_preview_failed)

    // 试听只服务单段回放，不抢朗读服务的播放器：合成好的文件用轻量 MediaPlayer 播
    val player = remember { MediaPlayer() }
    var lastPreviewPath by remember { mutableStateOf<String?>(null) }
    DisposableEffect(player) {
        player.setOnCompletionListener { latestOnIntent(SpeechStoryboardIntent.PreviewFinished) }
        // 异步解码失败只会哑掉，不留痕就又是「点了没声也看不到日志」
        player.setOnErrorListener { _, what, extra ->
            AppLog.put(
                "分镜试听播放失败：MediaPlayer what=$what extra=$extra " +
                    "path=${lastPreviewPath ?: "（未知）"}"
            )
            latestOnIntent(SpeechStoryboardIntent.PreviewFinished)
            true
        }
        onDispose { runCatching { player.release() } }
    }

    BackHandler(enabled = inChapterDetail) {
        onIntent(SpeechStoryboardIntent.BackToChapters)
    }

    var pendingDeleteChapters by remember { mutableStateOf<List<Int>?>(null) }

    LaunchedEffect(effects) {
        effects.collectLatest { effect ->
            when (effect) {
                is SpeechStoryboardEffect.ShowToast -> context.toastOnUi(effect.message)
                is SpeechStoryboardEffect.PlayPreview -> runCatching {
                    lastPreviewPath = effect.path
                    player.reset()
                    player.setDataSource(effect.path)
                    player.prepare()
                    player.start()
                }.onFailure { e ->
                    AppLog.put("分镜试听播放失败：${e.javaClass.name}: ${e.message} path=${effect.path}")
                    context.toastOnUi(previewFailed)
                }

                SpeechStoryboardEffect.StopPreviewPlayback -> runCatching {
                    if (player.isPlaying) player.stop()
                    player.reset()
                }
            }
        }
    }

    AppAlertDialog(
        show = pendingDeleteChapters != null,
        onDismissRequest = { pendingDeleteChapters = null },
        title = stringResource(R.string.delete),
        text = stringResource(R.string.speech_storyboard_delete_confirm),
        confirmText = stringResource(R.string.delete),
        onConfirm = {
            val targets = pendingDeleteChapters.orEmpty()
            pendingDeleteChapters = null
            if (targets.size == 1) {
                onIntent(SpeechStoryboardIntent.DeleteChapter(targets.first()))
            } else {
                onIntent(SpeechStoryboardIntent.DeleteSelectedChapters)
            }
        },
    )

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            StoryboardTopBar(
                state = state,
                scrollBehavior = scrollBehavior,
                inChapterDetail = inChapterDetail,
                onIntent = onIntent,
                onBack = onBack,
                onDeleteRequested = { pendingDeleteChapters = state.selectedChapterIndexes.toList() },
            )
        },
    ) { paddingValues ->
        when {
            state.isLoading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            inChapterDetail -> StoryboardList(
                state = state,
                onIntent = onIntent,
                contentPadding = paddingValues,
                modifier = Modifier.fillMaxSize(),
            )

            else -> ChapterList(
                state = state,
                onIntent = onIntent,
                onDeleteRequested = { chapterIndex -> pendingDeleteChapters = listOf(chapterIndex) },
                contentPadding = paddingValues,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StoryboardTopBar(
    state: SpeechStoryboardUiState,
    scrollBehavior: GlassTopAppBarScrollBehavior,
    inChapterDetail: Boolean,
    onIntent: (SpeechStoryboardIntent) -> Unit,
    onBack: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    GlassMediumFlexibleTopAppBar(
        title = stringResource(
            if (state.isManaging) {
                R.string.speech_storyboard_selected_count
            } else {
                R.string.speech_storyboard
            },
            state.selectedChapterIndexes.size,
        ),
        subtitle = state.chapterTitle,
        navigationIcon = {
            TopBarNavigationButton(
                onClick = {
                    when {
                        state.isManaging -> onIntent(SpeechStoryboardIntent.ExitManagement)
                        inChapterDetail -> onIntent(SpeechStoryboardIntent.BackToChapters)
                        else -> onBack()
                    }
                },
            )
        },
        actions = {
            if (inChapterDetail) {
                // 只有正在读的那一章能重新分析：其它章拿不到排版结果
                if (state.isCurrentChapter) {
                    TopBarActionButton(
                        imageVector = Icons.Default.Autorenew,
                        contentDescription = stringResource(R.string.speech_storyboard_reanalyze),
                        onClick = { onIntent(SpeechStoryboardIntent.Reanalyze) },
                    )
                }
                TopBarActionButton(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.refresh),
                    onClick = { onIntent(SpeechStoryboardIntent.Refresh) },
                )
            } else if (state.isManaging) {
                TopBarActionButton(
                    imageVector = Icons.Default.SelectAll,
                    contentDescription = stringResource(R.string.select_all),
                    onClick = { onIntent(SpeechStoryboardIntent.SelectAllChapters) },
                )
                TopBarActionButton(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    onClick = onDeleteRequested,
                )
                TopBarActionButton(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.complete),
                    onClick = { onIntent(SpeechStoryboardIntent.ExitManagement) },
                )
            } else {
                if (state.chapters.isNotEmpty()) {
                    TopBarActionButton(
                        imageVector = Icons.Default.Sort,
                        contentDescription = stringResource(R.string.speech_storyboard_manage),
                        onClick = { onIntent(SpeechStoryboardIntent.EnterManagement) },
                    )
                }
                TopBarActionButton(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.refresh),
                    onClick = { onIntent(SpeechStoryboardIntent.Refresh) },
                )
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun ChapterList(
    state: SpeechStoryboardUiState,
    onIntent: (SpeechStoryboardIntent) -> Unit,
    onDeleteRequested: (Int) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = adaptiveContentPadding(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.chapters.isEmpty()) {
            item(contentType = "empty") {
                AppText(
                    text = stringResource(R.string.speech_storyboard_chapters_empty),
                    style = LegadoTheme.typography.bodyMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else {
            items(
                items = state.chapters,
                key = StoryboardChapterUi::chapterIndex,
                contentType = { "chapter" },
            ) { chapter ->
                ChapterCard(
                    chapter = chapter,
                    managing = state.isManaging,
                    selected = chapter.chapterIndex in state.selectedChapterIndexes,
                    onClick = {
                        if (state.isManaging) {
                            onIntent(SpeechStoryboardIntent.ToggleChapterSelected(chapter.chapterIndex))
                        } else {
                            onIntent(SpeechStoryboardIntent.OpenChapter(chapter.chapterIndex))
                        }
                    },
                    onDelete = { onDeleteRequested(chapter.chapterIndex) },
                )
            }
        }
    }
}

@Composable
private fun ChapterCard(
    chapter: StoryboardChapterUi,
    managing: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        containerColor = LegadoTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (managing) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = null,
                    colors = CheckboxDefaults.colors(
                        checkedColor = LegadoTheme.colorScheme.primary,
                        checkmarkColor = LegadoTheme.colorScheme.onPrimary,
                        uncheckedColor = LegadoTheme.colorScheme.onSurfaceVariant,
                    ),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppText(
                    text = chapter.title,
                    style = LegadoTheme.typography.titleSmall,
                    maxLines = 1,
                )
                AppText(
                    text = chapterRowLabel(chapter),
                    style = LegadoTheme.typography.labelMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!managing && !chapter.isCurrent) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = LegadoTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable(onClick = onDelete)
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun chapterRowLabel(chapter: StoryboardChapterUi): String {
    val counts = if (chapter.segmentCount == 0) {
        stringResource(R.string.speech_storyboard_chapter_pending)
    } else {
        stringResource(
            R.string.speech_storyboard_chapter_row,
            chapter.segmentCount,
            chapter.characterCount,
        )
    }
    return if (chapter.isCurrent) {
        "$counts · ${stringResource(R.string.speech_storyboard_current_chapter)}"
    } else {
        counts
    }
}

@Composable
private fun StoryboardList(
    state: SpeechStoryboardUiState,
    onIntent: (SpeechStoryboardIntent) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    // 折叠是纯浏览状态，留在 UI；默认展开全部场景
    var collapsedScenes by remember(state.selectedChapterIndex) { mutableStateOf(setOf<Int>()) }
    var expandedItems by remember(state.selectedChapterIndex) { mutableStateOf(setOf<String>()) }
    // 没有场景数据时只有一个 sceneNumber=0 占位组，按扁平列表渲染、不出表头
    val grouped = state.scenes.any { it.sceneNumber > 0 }

    LazyColumn(
        modifier = modifier,
        contentPadding = adaptiveContentPadding(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(contentType = "summary") {
            SummaryCards(state = state)
        }
        if (state.scenes.isEmpty() || state.scenes.all { it.items.isEmpty() }) {
            item(contentType = "empty") {
                AppText(
                    text = stringResource(R.string.speech_storyboard_empty),
                    style = LegadoTheme.typography.bodyMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else {
            // 一个场景一张连续卡（学 NG 的分镜卷轴）：表头与段行同处一张玻璃卡内，
            // 段与段靠左侧时间轴线和留白分隔，不再一段一张碎卡
            state.scenes.forEach { scene ->
                val showHeader = grouped && scene.sceneNumber > 0
                val expanded = !showHeader || scene.sceneNumber !in collapsedScenes
                item(key = "scene_${scene.sceneNumber}", contentType = "scene") {
                    SceneCard(
                        scene = scene,
                        showHeader = showHeader,
                        expanded = expanded,
                        previewingItemId = state.previewingItemId,
                        expandedItems = expandedItems,
                        onToggleScene = {
                            collapsedScenes = if (expanded) {
                                collapsedScenes + scene.sceneNumber
                            } else {
                                collapsedScenes - scene.sceneNumber
                            }
                        },
                        onToggleDetails = { id ->
                            expandedItems = if (id in expandedItems) {
                                expandedItems - id
                            } else {
                                expandedItems + id
                            }
                        },
                        onPreview = { id -> onIntent(SpeechStoryboardIntent.PreviewSegment(id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SceneCard(
    scene: StoryboardSceneUi,
    showHeader: Boolean,
    expanded: Boolean,
    previewingItemId: String?,
    expandedItems: Set<String>,
    onToggleScene: () -> Unit,
    onToggleDetails: (String) -> Unit,
    onPreview: (String) -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = LegadoTheme.colorScheme.surfaceContainerLow,
    ) {
        if (showHeader) {
            SceneHeaderRow(
                scene = scene,
                expanded = expanded,
                onClick = onToggleScene,
            )
        }
        if (expanded) {
            scene.items.forEachIndexed { index, item ->
                SegmentRow(
                    item = item,
                    isFirst = index == 0,
                    isLast = index == scene.items.lastIndex,
                    previewing = previewingItemId == item.id,
                    detailsExpanded = item.id in expandedItems,
                    onToggleDetails = { onToggleDetails(item.id) },
                    onPreview = { onPreview(item.id) },
                )
            }
        }
    }
}

/** 场景表头：大号主色序号 + 标题/段数两行 + 旋转箭头（整行点击折叠展开） */
@Composable
private fun SceneHeaderRow(
    scene: StoryboardSceneUi,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(start = 8.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            text = scene.sceneNumber.coerceAtLeast(1).toString(),
            style = LegadoTheme.typography.titleMedium,
            color = LegadoTheme.colorScheme.primary,
            modifier = Modifier.width(34.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            AppText(
                text = scene.title.ifBlank {
                    stringResource(R.string.speech_storyboard_scene_title, scene.sceneNumber)
                },
                style = LegadoTheme.typography.bodyMedium,
                color = LegadoTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                text = stringResource(R.string.speech_storyboard_scene_segments, scene.items.size),
                style = LegadoTheme.typography.labelMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = null,
            tint = LegadoTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(22.dp)
                .rotate(if (expanded) 180f else 0f),
        )
    }
}

/**
 * 段行＝卷轴上的一节：左时间轴节点、说话人左锚、正文全展开、右上角小圆试听钮。
 * 整行点击展开/收起出处详情（对齐 NG 的行内节奏，卡片仍是我们的玻璃卡）。
 */
@Composable
private fun SegmentRow(
    item: StoryboardItemUi,
    isFirst: Boolean,
    isLast: Boolean,
    previewing: Boolean,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    onPreview: () -> Unit,
) {
    // 时间轴小圆点要对齐的是「角色行/音色行」这条文字线的视觉中线。
    // 行高由主题字体决定、各档不同，写死 dp 必然错位——改为量出来的中线：
    // 首行 onTextLayout 报一次，量不到时（零高占位等）退回按字号估的中线。
    // density 必须在组合上下文取好，remember 的 lambda 里读不了 LocalDensity
    val density = LocalDensity.current
    val anchorTopPx = with(density) { 14.dp.toPx() }
    var nodeCenterY by remember(item.id) {
        mutableFloatStateOf(anchorTopPx + with(density) { 9.5.dp.toPx() })
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleDetails)
            .height(IntrinsicSize.Min),
    ) {
        TimelineRail(
            isFirst = isFirst,
            isLast = isLast,
            nodeCenterY = nodeCenterY,
            modifier = Modifier
                .width(26.dp)
                .fillMaxHeight(),
        )
        Column(
            modifier = Modifier
                .width(56.dp)
                .padding(top = 14.dp, bottom = 14.dp),
        ) {
            // 左锚只放两三个字的角色词，名字挪到右边细灰行开头，不会再折成两行；
            // 字号跟右边细灰行、「第N段」同一档（labelMedium），三处一致
            AppText(
                text = roleWord(item.role),
                style = LegadoTheme.typography.labelMedium,
                color = when (item.role) {
                    StoryboardRole.Narrator -> LegadoTheme.colorScheme.onSurfaceVariant
                    StoryboardRole.Unknown -> LegadoTheme.colorScheme.error
                    else -> LegadoTheme.colorScheme.primary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { layout ->
                    if (layout.lineCount > 0) {
                        val center = (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f + anchorTopPx
                        if (abs(center - nodeCenterY) > 0.5f) nodeCenterY = center
                    }
                },
            )
            AppText(
                text = stringResource(R.string.speech_storyboard_paragraph, item.paragraphIndex + 1),
                style = LegadoTheme.typography.labelMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 14.dp, bottom = 14.dp, end = 6.dp),
        ) {
            AppText(
                text = statusLabel(item),
                style = LegadoTheme.typography.labelMedium,
                color = if (item.voiceName.isEmpty()) {
                    LegadoTheme.colorScheme.error
                } else {
                    LegadoTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            AppText(
                // 原文段落普遍带段首缩进（全角空格最常见），卡片里顶格显示才整齐；
                // 只洗展示这一层，落库文本与试听合成拿到的仍是原文（与 NG 同一口径）
                text = item.text.trimStart(' ', '\t', '\u3000'),
                style = LegadoTheme.typography.bodyMedium,
                // 本页是核对用的密集列表，正文按用户口径收到 13sp，与小字各档同径
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (detailsExpanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
                    color = LegadoTheme.colorScheme.outlineVariant,
                )
                AppText(
                    text = detailLabel(item),
                    style = LegadoTheme.typography.labelMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        PreviewButton(
            previewable = item.previewable,
            previewing = previewing,
            onClick = onPreview,
            modifier = Modifier.padding(top = 14.dp, end = 12.dp),
        )
    }
}

/** 时间轴（学 NG 的 StoryboardTimeline）：段段相连的竖线，首末段空心环、中间实心点 */
@Composable
private fun TimelineRail(
    isFirst: Boolean,
    isLast: Boolean,
    nodeCenterY: Float,
    modifier: Modifier = Modifier,
) {
    val color = LegadoTheme.colorScheme.primary
    Canvas(modifier) {
        val centerX = size.width / 2f
        // 节点圆心 = 左锚首行文字的中线（SegmentRow 实测传入），不再写死 21dp
        val centerY = nodeCenterY
        val lineWidth = 2.dp.toPx()
        val endpointRadius = 6.dp.toPx()
        val nodeRadius = if (isFirst || isLast) endpointRadius else 4.dp.toPx()
        if (!isFirst) {
            drawLine(
                color = color.copy(alpha = 0.52f),
                start = Offset(centerX, 0f),
                end = Offset(centerX, centerY - nodeRadius),
                strokeWidth = lineWidth,
            )
        }
        if (!isLast) {
            drawLine(
                color = color.copy(alpha = 0.52f),
                start = Offset(centerX, centerY + nodeRadius),
                end = Offset(centerX, size.height),
                strokeWidth = lineWidth,
            )
        }
        if (isFirst || isLast) {
            drawCircle(
                color = color,
                radius = endpointRadius - 1.dp.toPx(),
                center = Offset(centerX, centerY),
                style = Stroke(width = lineWidth),
            )
            drawCircle(color = color, radius = 2.dp.toPx(), center = Offset(centerX, centerY))
        } else {
            drawCircle(color = color, radius = 4.dp.toPx(), center = Offset(centerX, centerY))
        }
    }
}

/** 试听钮收成角标式：主色 12% 圆底 + 16dp 图标，小一号把行宽还给正文 */
@Composable
private fun PreviewButton(
    previewable: Boolean,
    previewing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(28.dp)
            .background(
                LegadoTheme.colorScheme.primary.copy(alpha = if (previewable) 0.12f else 0.05f),
                CircleShape,
            )
            .clickable(enabled = previewable, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            previewing -> CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = LegadoTheme.colorScheme.primary,
            )

            else -> Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = stringResource(R.string.speech_storyboard_preview),
                tint = if (previewable) {
                    LegadoTheme.colorScheme.primary
                } else {
                    LegadoTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * 详情页顶部拆成两张卡、内容不互相重复：
 * 配置卡＝现在按什么在读（分析模式 / 多角色朗读），
 * 数据卡＝这一章有多少（场景/分段(已分配/总数)/对白/说话人四格）。
 * 之前两行裸文本里段数出现两遍、「已分配音色 39 段」还跟「39 段对白」撞眼。
 */
@Composable
private fun SummaryCards(state: SpeechStoryboardUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = LegadoTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SummaryLabelRow(
                    label = stringResource(R.string.speech_storyboard_label_analysis_mode),
                    value = analysisModeLabel(state.analysisMode),
                )
                SummaryLabelRow(
                    label = stringResource(R.string.use_multi_speaker),
                    value = stringResource(
                        if (state.multiSpeakerEnabled) {
                            R.string.speech_storyboard_status_on
                        } else {
                            R.string.speech_storyboard_status_off
                        },
                    ),
                )
            }
        }
        val summary = state.summary
        if (summary != null) {
            val voicedSegments = state.scenes.sumOf { scene ->
                scene.items.count { it.voiceName.isNotEmpty() }
            }
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = LegadoTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (summary.sceneCount > 0) {
                        StatCell(
                            summary.sceneCount.toString(),
                            stringResource(R.string.speech_storyboard_stat_scenes),
                        )
                    }
                    // 「分段」格直接写成 已分配/总数：这一格自己讲完进度，
                    // 下面那行「已分配音色 x/y 段」就是重复，删掉
                    VoicedSegmentCell(
                        voiced = voicedSegments,
                        total = summary.segmentCount,
                        label = stringResource(R.string.speech_storyboard_stat_segments),
                    )
                    StatCell(
                        summary.dialogueCount.toString(),
                        stringResource(R.string.speech_storyboard_stat_dialogues),
                    )
                    StatCell(
                        summary.personCount.toString(),
                        stringResource(R.string.speech_storyboard_stat_speakers),
                    )
                }
            }
        }
    }
}

/** 配置卡一行：左右都收 13sp（值不再用正文档），左灰右亮 */
@Composable
private fun SummaryLabelRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppText(
            text = label,
            style = LegadoTheme.typography.labelMedium,
            color = LegadoTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(76.dp),
            maxLines = 1,
        )
        AppText(
            text = value,
            style = LegadoTheme.typography.labelMedium,
            color = LegadoTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 数据卡格子：数字在上（主色）、小标签在下，均分列居中 */
@Composable
private fun RowScope.StatCell(value: String, label: String) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppText(
            text = value,
            style = LegadoTheme.typography.titleMedium,
            color = LegadoTheme.colorScheme.primary,
        )
        AppText(
            text = label,
            style = LegadoTheme.typography.labelMedium,
            color = LegadoTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
            maxLines = 1,
        )
    }
}

/** 带音色进度的「分段」格：数字位写成 已分配/总数，分母降一号灰字，标签仍是「分段」 */
@Composable
private fun RowScope.VoicedSegmentCell(voiced: Int, total: Int, label: String) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.Center) {
            AppText(
                text = voiced.toString(),
                style = LegadoTheme.typography.titleMedium,
                color = LegadoTheme.colorScheme.primary,
            )
            AppText(
                text = "/$total",
                style = LegadoTheme.typography.labelMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppText(
            text = label,
            style = LegadoTheme.typography.labelMedium,
            color = LegadoTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun analysisModeLabel(mode: String): String = when (mode) {
    "rule_with_ai" -> stringResource(R.string.speech_analysis_rule_ai)
    "ai_understanding" -> stringResource(R.string.speech_analysis_ai)
    else -> stringResource(R.string.speech_analysis_rule)
}

@Composable
private fun roleWord(role: StoryboardRole): String = when (role) {
    StoryboardRole.Narrator -> stringResource(R.string.voice_role_narrator)
    StoryboardRole.Thought -> stringResource(R.string.speech_role_thought)
    StoryboardRole.Character -> stringResource(R.string.speech_role_dialogue)
    StoryboardRole.Unknown -> stringResource(R.string.voice_role_unknown)
}

/**
 * 正文上方细灰行：角色名 · 情绪 · 音色。
 * 音色的 displayName 在 http 书源是「引擎名 · 音色名」拼出来的
 * （HttpTtsVoiceCatalog），行内只留最后一段音色名，引擎名不进列表。
 */
@Composable
private fun statusLabel(item: StoryboardItemUi): String {
    val voice = item.voiceName.substringAfterLast(" · ")
        .ifEmpty { stringResource(R.string.voice_not_assigned) }
    val emotion = item.emotion.takeIf { it.isNotEmpty() && it != "neutral" }
    return listOfNotNull(
        item.speakerName.takeIf { it.isNotEmpty() },
        emotion,
        voice,
    ).joinToString(" · ")
}

@Composable
private fun detailLabel(item: StoryboardItemUi): String {
    val source = when (item.source) {
        StoryboardSource.Rule -> stringResource(R.string.speech_storyboard_source_rule)
        StoryboardSource.Local -> stringResource(R.string.speech_storyboard_source_local)
        StoryboardSource.Ai -> stringResource(R.string.speech_storyboard_source_ai)
        StoryboardSource.User -> stringResource(R.string.speech_storyboard_source_user)
        StoryboardSource.Fallback -> stringResource(R.string.speech_storyboard_source_fallback)
    }
    val parts = buildList {
        add(source)
        if (item.locked) add(stringResource(R.string.speech_storyboard_locked))
        if (item.confidence > 0f) {
            add(stringResource(R.string.speech_storyboard_confidence, (item.confidence * 100).toInt()))
        }
        if (item.sceneIndex > 0 && item.sceneTitle.isNotBlank()) {
            add(
                stringResource(R.string.speech_storyboard_scene_title, item.sceneIndex) +
                    " · " + item.sceneTitle,
            )
        }
    }
    return parts.joinToString(" · ")
}
