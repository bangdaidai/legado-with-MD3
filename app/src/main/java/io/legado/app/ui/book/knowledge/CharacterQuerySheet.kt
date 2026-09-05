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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.progressIndicator.AppCircularProgressIndicator
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.icon.AppIcons
import org.koin.androidx.compose.koinViewModel

/**
 * 人物速查卡：划词菜单入口弹出。档案命中直接展示；未命中展示检索锚点
 * （最初登场 / 最近出场，点击跳章节）并让 AI 依据摘录归纳介绍。
 * 排版跟随 AppModalBottomSheet 规范：组件自带 16dp 水平内边距，内部只做纵向节奏；
 * 保存动作放标题栏 endAction 槽位，操作反馈走 Toast。
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
            if (state.profile == null && state.aiSummary.isNotBlank()) {
                MediumTonalButton(
                    onClick = { viewModel.onIntent(CharacterQueryIntent.SaveProfile) },
                    enabled = state.saveState != CharacterQueryUiState.SaveState.Saving &&
                        state.saveState != CharacterQueryUiState.SaveState.Saved,
                    icon = AppIcons.Check,
                    contentDescription = stringResource(R.string.character_save_profile),
                )
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
                    ProfileSection(profile = profile)
                    SectionDivider()
                }

                AppearanceSection(
                    title = stringResource(R.string.character_first_appearance),
                    appearance = state.firstAppearance,
                    hint = if (!state.searchFullyCovered) {
                        stringResource(R.string.character_partial_cache_hint)
                    } else null,
                    onJump = { onIntent(CharacterQueryIntent.JumpTo(it)) },
                )
                AppearanceSection(
                    title = stringResource(R.string.character_latest_appearance),
                    appearance = state.latestAppearance,
                    hint = null,
                    onJump = { onIntent(CharacterQueryIntent.JumpTo(it)) },
                )

                TraceSection(state = state, onIntent = onIntent)

                SectionDivider()
                AiSummarySection(state = state, onIntent = onIntent)
            }
        }
    }
}

@Composable
private fun ProfileSection(profile: io.legado.app.data.entities.BookCharacterProfile) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
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
private fun AppearanceSection(
    title: String,
    appearance: CharacterQueryUiState.CharacterAppearance?,
    hint: String?,
    onJump: (chapterIndex: Int) -> Unit,
) {
    if (appearance == null) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onJump(appearance.chapterIndex) }
            .padding(vertical = 8.dp),
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
private fun TraceSection(
    state: CharacterQueryUiState,
    onIntent: (CharacterQueryIntent) -> Unit,
) {
    if (state.searchFullyCovered) return
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
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
        when {
            state.tracing -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                AppCircularProgressIndicator()
                Text(
                    text = stringResource(R.string.character_trace_running),
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.traceFinished && state.firstAppearance == null -> Text(
                text = stringResource(R.string.character_trace_not_found),
                style = LegadoTheme.typography.labelSmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            !state.traceFinished -> TextButton(
                onClick = { onIntent(CharacterQueryIntent.TraceFromStart) },
            ) {
                Text(text = stringResource(R.string.character_trace_button))
            }
        }
    }
}

@Composable
private fun AiSummarySection(
    state: CharacterQueryUiState,
    onIntent: (CharacterQueryIntent) -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        when {
            state.aiLoading -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppCircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.character_ai_loading),
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.aiSummary.isNotBlank() -> {
                Text(
                    text = stringResource(R.string.character_ai_summary_label),
                    style = LegadoTheme.typography.labelMedium,
                    color = LegadoTheme.colorScheme.primary,
                )
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
                Text(
                    text = state.error,
                    style = LegadoTheme.typography.bodySmall,
                    color = LegadoTheme.colorScheme.error,
                )
                TextButton(onClick = { onIntent(CharacterQueryIntent.Retry) }) {
                    Text(text = stringResource(R.string.retry))
                }
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = LegadoTheme.colorScheme.outlineVariant,
    )
}
