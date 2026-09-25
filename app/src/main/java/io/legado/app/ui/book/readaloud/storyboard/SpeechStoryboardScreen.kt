package io.legado.app.ui.book.readaloud.storyboard

import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AppText(
                    text = storyboardSummary(state),
                    style = LegadoTheme.typography.bodyMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                state.summary?.let { summary ->
                    AppText(
                        text = statsLabel(summary),
                        style = LegadoTheme.typography.labelLarge,
                        color = LegadoTheme.colorScheme.primary,
                    )
                }
            }
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
            state.scenes.forEach { scene ->
                val showHeader = grouped && scene.sceneNumber > 0
                val expanded = !showHeader || scene.sceneNumber !in collapsedScenes
                if (showHeader) {
                    item(key = "scene_${scene.sceneNumber}", contentType = "scene") {
                        SceneHeader(
                            scene = scene,
                            expanded = expanded,
                            onClick = {
                                collapsedScenes = if (expanded) {
                                    collapsedScenes + scene.sceneNumber
                                } else {
                                    collapsedScenes - scene.sceneNumber
                                }
                            },
                        )
                    }
                }
                if (expanded) {
                    items(
                        items = scene.items,
                        key = StoryboardItemUi::id,
                        contentType = { "segment" },
                    ) { item ->
                        StoryboardCard(
                            item = item,
                            previewing = state.previewingItemId == item.id,
                            detailsExpanded = item.id in expandedItems,
                            onToggleDetails = {
                                expandedItems = if (item.id in expandedItems) {
                                    expandedItems - item.id
                                } else {
                                    expandedItems + item.id
                                }
                            },
                            onPreview = { onIntent(SpeechStoryboardIntent.PreviewSegment(item.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneHeader(
    scene: StoryboardSceneUi,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        containerColor = LegadoTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(
                text = stringResource(R.string.speech_storyboard_scene_title, scene.sceneNumber),
                style = LegadoTheme.typography.labelLarge,
                color = LegadoTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            AppText(
                text = scene.title.ifBlank {
                    stringResource(R.string.speech_storyboard_scene_segments, scene.items.size)
                },
                style = LegadoTheme.typography.bodyMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (scene.title.isNotBlank()) {
                AppText(
                    text = stringResource(R.string.speech_storyboard_scene_segments, scene.items.size),
                    style = LegadoTheme.typography.labelMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(6.dp))
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
}

@Composable
private fun StoryboardCard(
    item: StoryboardItemUi,
    previewing: Boolean,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    onPreview: () -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onToggleDetails,
        containerColor = LegadoTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    text = roleLabel(item),
                    style = LegadoTheme.typography.labelLarge,
                    color = when (item.role) {
                        StoryboardRole.Narrator -> LegadoTheme.colorScheme.onSurfaceVariant
                        StoryboardRole.Unknown -> LegadoTheme.colorScheme.error
                        else -> LegadoTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                PreviewButton(
                    previewable = item.previewable,
                    previewing = previewing,
                    onClick = onPreview,
                )
            }
            AppText(
                text = item.text,
                style = LegadoTheme.typography.bodyMedium,
                maxLines = if (detailsExpanded) Int.MAX_VALUE else 6,
            )
            AppText(
                text = footerLabel(item),
                style = LegadoTheme.typography.labelMedium,
                color = if (item.voiceName.isEmpty()) {
                    LegadoTheme.colorScheme.error
                } else {
                    LegadoTheme.colorScheme.onSurfaceVariant
                },
            )
            if (detailsExpanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = LegadoTheme.colorScheme.outlineVariant,
                )
                AppText(
                    text = detailLabel(item),
                    style = LegadoTheme.typography.labelMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreviewButton(previewable: Boolean, previewing: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(enabled = previewable, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when {
            previewing -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
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
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun storyboardSummary(state: SpeechStoryboardUiState): String {
    val mode = when (state.analysisMode) {
        "rule_with_ai" -> stringResource(R.string.speech_analysis_rule_ai)
        "ai_understanding" -> stringResource(R.string.speech_analysis_ai)
        else -> stringResource(R.string.speech_analysis_rule)
    }
    val multiSpeaker = if (state.multiSpeakerEnabled) {
        stringResource(R.string.speech_storyboard_multi_on)
    } else {
        stringResource(R.string.speech_storyboard_multi_off)
    }
    val segmentCount = state.summary?.segmentCount ?: 0
    val voiceCount = state.scenes.sumOf { scene -> scene.items.count { it.voiceName.isNotEmpty() } }
    return stringResource(R.string.speech_storyboard_summary, mode, multiSpeaker, segmentCount, voiceCount)
}

@Composable
private fun statsLabel(summary: StoryboardSummaryUi): String = if (summary.sceneCount > 0) {
    stringResource(
        R.string.speech_storyboard_stats_scenes,
        summary.sceneCount,
        summary.segmentCount,
        summary.dialogueCount,
        summary.personCount,
    )
} else {
    stringResource(
        R.string.speech_storyboard_stats_flat,
        summary.segmentCount,
        summary.dialogueCount,
        summary.personCount,
    )
}

@Composable
private fun roleLabel(item: StoryboardItemUi): String {
    val role = when (item.role) {
        StoryboardRole.Narrator -> stringResource(R.string.voice_role_narrator)
        StoryboardRole.Thought -> stringResource(R.string.speech_role_thought)
        StoryboardRole.Character -> stringResource(R.string.speech_role_dialogue)
        StoryboardRole.Unknown -> stringResource(R.string.voice_role_unknown)
    }
    val speaker = item.speakerName.ifEmpty { null }
    return listOfNotNull(role, speaker).joinToString(" · ")
}

@Composable
private fun footerLabel(item: StoryboardItemUi): String {
    val voice = item.voiceName.ifEmpty { stringResource(R.string.voice_not_assigned) }
    val emotion = item.emotion.takeIf { it.isNotEmpty() && it != "neutral" }
    val paragraph = stringResource(R.string.speech_storyboard_paragraph, item.paragraphIndex + 1)
    return listOfNotNull(voice, paragraph, emotion).joinToString(" · ")
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
