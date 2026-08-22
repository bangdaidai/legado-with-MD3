package io.legado.app.ui.book.readaloud.storyboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
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

    BackHandler(enabled = inChapterDetail) {
        onIntent(SpeechStoryboardIntent.BackToChapters)
    }

    LaunchedEffect(effects) {
        effects.collectLatest { effect ->
            when (effect) {
                is SpeechStoryboardEffect.ShowToast -> context.toastOnUi(effect.message)
            }
        }
    }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.speech_storyboard),
                subtitle = state.chapterTitle,
                navigationIcon = {
                    TopBarNavigationButton(
                        onClick = {
                            if (inChapterDetail) {
                                onIntent(SpeechStoryboardIntent.BackToChapters)
                            } else {
                                onBack()
                            }
                        },
                    )
                },
                actions = {
                    // 只有正在读的那一章能重新分析：其它章拿不到排版结果
                    if (inChapterDetail && state.isCurrentChapter) {
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
                },
                scrollBehavior = scrollBehavior,
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
                contentPadding = paddingValues,
                modifier = Modifier.fillMaxSize(),
            )

            else -> ChapterList(
                state = state,
                onIntent = onIntent,
                contentPadding = paddingValues,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ChapterList(
    state: SpeechStoryboardUiState,
    onIntent: (SpeechStoryboardIntent) -> Unit,
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
                ChapterCard(chapter) {
                    onIntent(SpeechStoryboardIntent.OpenChapter(chapter.chapterIndex))
                }
            }
        }
    }
}

@Composable
private fun ChapterCard(chapter: StoryboardChapterUi, onClick: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        containerColor = LegadoTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppText(
                text = chapter.title,
                style = LegadoTheme.typography.titleSmall,
            )
            AppText(
                text = chapterRowLabel(chapter),
                style = LegadoTheme.typography.labelMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
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
        item(contentType = "summary") {
            AppText(
                text = storyboardSummary(state),
                style = LegadoTheme.typography.bodyMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (state.items.isEmpty()) {
            item(contentType = "empty") {
                AppText(
                    text = stringResource(R.string.speech_storyboard_empty),
                    style = LegadoTheme.typography.bodyMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else {
            items(
                items = state.items,
                key = StoryboardItemUi::id,
                contentType = { "segment" },
            ) { item ->
                StoryboardCard(item)
            }
        }
    }
}

@Composable
private fun StoryboardCard(item: StoryboardItemUi) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = LegadoTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppText(
                text = roleLabel(item),
                style = LegadoTheme.typography.labelLarge,
                color = when (item.role) {
                    StoryboardRole.Narrator -> LegadoTheme.colorScheme.onSurfaceVariant
                    StoryboardRole.Unknown -> LegadoTheme.colorScheme.error
                    else -> LegadoTheme.colorScheme.primary
                },
            )
            AppText(
                text = item.text,
                style = LegadoTheme.typography.bodyMedium,
            )
            AppText(
                text = voiceLabel(item),
                style = LegadoTheme.typography.labelMedium,
                color = if (item.voiceName.isEmpty()) {
                    LegadoTheme.colorScheme.error
                } else {
                    LegadoTheme.colorScheme.onSurfaceVariant
                },
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
    return stringResource(
        R.string.speech_storyboard_summary,
        mode,
        multiSpeaker,
        state.items.size,
        state.items.count { it.voiceName.isNotEmpty() },
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
    val emotion = item.emotion.ifEmpty { null }
    return listOfNotNull(role, speaker, emotion).joinToString(" · ")
}

@Composable
private fun voiceLabel(item: StoryboardItemUi): String =
    item.voiceName.ifEmpty { stringResource(R.string.voice_not_assigned) }
