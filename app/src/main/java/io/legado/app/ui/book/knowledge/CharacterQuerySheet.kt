package io.legado.app.ui.book.knowledge

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.progressIndicator.AppCircularProgressIndicator
import org.koin.androidx.compose.koinViewModel

/**
 * 人物速查卡：划词菜单入口弹出。档案命中直接展示；未命中展示检索锚点
 * （最初登场 / 最近出场，点击跳章节）并让 AI 依据摘录归纳介绍。
 * 布局规范：AppModalBottomSheet 提供 16dp 横向边距；内容按「档案 / 原文出处 /
 * 追查 / AI 归纳」分卡片（GlassCard，内边距 12dp，卡片间 4dp），区块内不再裸排文本。
 */
@Composable
fun CharacterQuerySheet(
    show: Boolean,
    name: String,
    onDismissRequest: () -> Unit,
    onJumpToChapter: (chapterIndex: Int) -> Unit,
    viewModel: CharacterQueryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(show, name) {
        if (show && name.isNotBlank()) {
            viewModel.onIntent(CharacterQueryIntent.Load(name))
        }
    }
    LaunchedEffect(viewModel.effects) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CharacterQueryEffect.JumpToChapter -> onJumpToChapter(effect.chapterIndex)
                is CharacterQueryEffect.ShowToast ->
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = state.name.ifBlank { name },
        endAction = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 从头追查：对话框级动作（更新原文出处 + 重跑 AI 归纳），与保存在一起放标题栏
                if (!state.searchFullyCovered && !state.traceFinished) {
                    MediumTonalButton(
                        onClick = { viewModel.onIntent(CharacterQueryIntent.TraceFromStart) },
                        enabled = !state.tracing,
                        icon = AppIcons.Refresh,
                        contentDescription = stringResource(R.string.character_trace_start),
                    )
                }
                if (state.profile == null && state.aiSummary.isNotBlank()) {
                    MediumTonalButton(
                        onClick = { viewModel.onIntent(CharacterQueryIntent.SaveProfile) },
                        enabled = state.saveState != CharacterQueryUiState.SaveState.Saving &&
                            state.saveState != CharacterQueryUiState.SaveState.Saved,
                        icon = AppIcons.Check,
                        contentDescription = stringResource(R.string.character_save_profile),
                    )
                }
            }
        },
    ) {
        CharacterQueryContent(
            state = state,
            onIntent = viewModel::onIntent,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CharacterQueryContent(
    state: CharacterQueryUiState,
    onIntent: (CharacterQueryIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AppCircularProgressIndicator()
                }
            }

            else -> {
                state.profile?.let { profile ->
                    SectionCard {
                        ProfileSection(profile = profile)
                    }
                }

                if (state.firstAppearance != null || state.latestAppearance != null) {
                    AnchorCard(state = state, onIntent = onIntent)
                } else if (!state.searchFullyCovered && !state.traceFinished) {
                    // 缓存不全且一无所获时也给出追查状态反馈，否则标题栏按钮像没反应
                    AnchorCard(state = state, onIntent = onIntent)
                }

                if (state.aiLoading || state.aiSummary.isNotBlank() || state.error != null) {
                    AiSummaryCard(state = state, onIntent = onIntent)
                }
            }
        }
    }
}

/** 统一卡片容器：4dp 纵向间距 + 12dp 内边距，所有区块共用，消灭裸排文本。 */
@Composable
private fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        containerColor = LegadoTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
}

@Composable
private fun ProfileSection(profile: io.legado.app.data.entities.BookCharacterProfile) {
    Column {
        SectionLabel(text = stringResource(R.string.character_profile_hit))
        if (profile.role.isNotBlank() || profile.personality.isNotBlank()) {
            Text(
                text = listOf(profile.role, profile.personality)
                    .filter(String::isNotBlank)
                    .joinToString(" · "),
                style = LegadoTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (profile.summary.isNotBlank()) {
            Text(
                text = profile.summary,
                style = LegadoTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 分区小标题：标签样式统一收口，层级为「标签 → 正文 → 辅文」三级。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = LegadoTheme.typography.labelMedium,
        color = LegadoTheme.colorScheme.primary,
    )
}

@Composable
private fun AnchorCard(
    state: CharacterQueryUiState,
    onIntent: (CharacterQueryIntent) -> Unit,
) {
    SectionCard {
        state.firstAppearance?.let { appearance ->
            AppearanceRow(
                title = stringResource(R.string.character_first_appearance),
                appearance = appearance,
                hint = if (!state.searchFullyCovered) {
                    stringResource(R.string.character_partial_cache_hint)
                } else null,
                onJump = { onIntent(CharacterQueryIntent.JumpTo(it)) },
            )
        } ?: run {
            // 还没有任何登场锚点：给出占位说明，避免卡片只剩半截
            SectionLabel(text = stringResource(R.string.character_first_appearance))
            Text(
                text = stringResource(R.string.character_first_not_found),
                style = LegadoTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (state.firstAppearance != null && state.latestAppearance != null) {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = LegadoTheme.colorScheme.outlineVariant,
            )
        }
        state.latestAppearance?.let { appearance ->
            AppearanceRow(
                title = stringResource(R.string.character_latest_appearance),
                appearance = appearance,
                hint = null,
                onJump = { onIntent(CharacterQueryIntent.JumpTo(it)) },
            )
        }
        TraceStatusArea(state = state)
    }
}

/** 追查进行中/未命中的反馈：落在原文出处卡内，因为它更新的就是这里。 */
@Composable
private fun TraceStatusArea(state: CharacterQueryUiState) {
    if (state.searchFullyCovered) return
    val showProgress = state.tracing || state.traceProgress != null
    val showNotFound = state.traceFinished && state.firstAppearance == null
    if (!showProgress && !showNotFound) return
    Column(modifier = Modifier.padding(top = 8.dp)) {
        if (showProgress) {
            state.traceProgress?.let { progress ->
                Text(
                    text = stringResource(
                        R.string.character_trace_progress,
                        progress.scanned,
                        progress.total,
                        progress.downloaded,
                    ),
                    style = LegadoTheme.typography.labelSmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.tracing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    AppCircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.character_trace_running),
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (showNotFound) {
            Text(
                text = stringResource(R.string.character_trace_not_found),
                style = LegadoTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppearanceRow(
    title: String,
    appearance: CharacterQueryUiState.CharacterAppearance,
    hint: String?,
    onJump: (chapterIndex: Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onJump(appearance.chapterIndex) },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(text = title)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = appearance.chapterTitle,
                style = LegadoTheme.typography.labelMedium,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        Text(
            text = appearance.excerpt,
            style = LegadoTheme.typography.bodySmall,
            color = LegadoTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        hint?.let {
            Text(
                text = it,
                style = LegadoTheme.typography.labelSmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun AiSummaryCard(
    state: CharacterQueryUiState,
    onIntent: (CharacterQueryIntent) -> Unit,
) {
    SectionCard {
        when {
            state.aiLoading -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppCircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
                Text(
                    text = stringResource(R.string.character_ai_loading),
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.aiSummary.isNotBlank() -> {
                SectionLabel(text = stringResource(R.string.character_ai_summary_label))
                Text(
                    text = state.aiSummary,
                    style = LegadoTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                when (state.saveState) {
                    CharacterQueryUiState.SaveState.Saving -> Text(
                        text = stringResource(R.string.character_saving),
                        style = LegadoTheme.typography.labelSmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    CharacterQueryUiState.SaveState.Saved -> Text(
                        text = stringResource(R.string.character_saved),
                        style = LegadoTheme.typography.labelSmall,
                        color = LegadoTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    else -> Unit
                }
            }

            state.error != null -> {
                SectionLabel(text = stringResource(R.string.character_ai_summary_label))
                Text(
                    text = state.error,
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    SmallPlainButton(
                        onClick = { onIntent(CharacterQueryIntent.Retry) },
                        text = stringResource(R.string.retry),
                    )
                }
            }
        }
    }
}
