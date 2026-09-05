package io.legado.app.ui.config.ai.log

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.ui.widget.components.icon.AppIcons
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import org.koin.androidx.compose.koinViewModel

@Composable
fun AiLogRouteScreen(
    onBackClick: () -> Unit,
    viewModel: AiLogViewModel = koinViewModel(),
) {
    AiLogScreen(
        state = viewModel.uiState.collectAsStateWithLifecycle().value,
        effects = viewModel.effects,
        onIntent = viewModel::onIntent,
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AiLogScreen(
    state: AiLogUiState,
    effects: Flow<AiLogEffect>,
    onIntent: (AiLogIntent) -> Unit,
    onBackClick: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val hasLogs = state.logs.isNotEmpty()
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val expandedKeys = remember { mutableStateMapOf<String, Boolean>() }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        effects.collectLatest { effect ->
            when (effect) {
                is AiLogEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.ai_log),
                navigationIcon = { TopBarNavigationButton(onClick = onBackClick) },
                scrollBehavior = scrollBehavior,
                actions = {
                    if (hasLogs) {
                        TopBarActionButton(
                            onClick = { onIntent(AiLogIntent.CopyAll) },
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.ai_log_copy_all),
                        )
                        TopBarActionButton(
                            onClick = { onIntent(AiLogIntent.Clear) },
                            imageVector = AppIcons.Delete,
                            contentDescription = stringResource(R.string.ai_log_clear),
                        )
                    }
                },
            )
        }
    ) { paddingValues ->
        if (!state.enabled) {
            EmptyHint(stringResource(R.string.ai_log_disabled))
        } else if (state.logs.isEmpty()) {
            EmptyHint(stringResource(R.string.ai_log_empty))
        } else {
            LazyColumn(
                state = listState,
                contentPadding = adaptiveContentPadding(
                    top = paddingValues.calculateTopPadding(),
                    bottom = 120.dp,
                )
            ) {
                items(state.logs, key = { it.stableKey() }) { item ->
                    val key = item.stableKey()
                    val wasExpanded = remember { mutableStateOf(expandedKeys[key] == true) }
                    val isExpanded = expandedKeys[key] == true
                    LaunchedEffect(isExpanded) {
                        if (wasExpanded.value && !isExpanded) {
                            val index = state.logs.indexOfFirst { it.stableKey() == key }
                            if (index >= 0) {
                                listState.animateScrollToItem(index)
                            }
                        }
                        wasExpanded.value = isExpanded
                    }
                    LogCard(
                        item = item,
                        expanded = isExpanded,
                        onToggleExpand = { expandedKeys[key] = expandedKeys[key] != true },
                        onCopy = { onIntent(AiLogIntent.CopyItem(item)) },
                    )
                }
            }
        }
    }
}

private fun AiLogItemUi.stableKey(): String = "$timeText|$scenario|$kind|$provider|$model"

private fun formatRelativeMs(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    else -> String.format(java.util.Locale.getDefault(), "%.1fs", ms / 1000.0)
}

@Composable
private fun LogCard(
    item: AiLogItemUi,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopy: () -> Unit,
) {
    val statusColor = if (item.success) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    val hasLongContent = item.summary.isNotBlank() ||
        item.prompt.isNotBlank() ||
        item.reasoning.isNotBlank() ||
        item.output.isNotBlank() ||
        (!item.success && !item.error.isNullOrBlank())
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(enabled = hasLongContent, onClick = onToggleExpand),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.timeText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${item.kind} · ${if (item.success) stringResource(R.string.success) else stringResource(R.string.fail)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                )
            }
            Text(
                text = item.scenario,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                text = "${item.provider} / ${item.model}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (item.steps.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.ai_log_steps),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                item.steps.forEach { step ->
                    Text(
                        text = "+${formatRelativeMs(step.relativeMs)} ${step.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, start = 8.dp),
                    )
                }
            }
            if (item.summary.isNotBlank()) {
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                )
            }
            if (expanded) {
                LogSection(stringResource(R.string.ai_log_prompt), item.prompt)
                LogSection(stringResource(R.string.ai_log_reasoning), item.reasoning)
                LogSection(stringResource(R.string.ai_log_output), item.output)
            }
            Text(
                text = item.durationText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (!item.success && !item.error.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.ai_log_error_format, item.error),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                )
            }
            if (hasLongContent) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.ai_log_copy),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onCopy)
                        .padding(2.dp)
                        .size(16.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(if (expanded) R.string.collapse else R.string.expand),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
            }
        }
    }
}

@Composable
private fun LogSection(title: String, text: String) {
    if (text.isBlank()) return
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
