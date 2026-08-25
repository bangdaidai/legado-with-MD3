package io.legado.app.ui.config.ai.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
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

@Composable
fun AiLogScreen(
    state: AiLogUiState,
    effects: Flow<AiLogEffect>,
    onIntent: (AiLogIntent) -> Unit,
    onBackClick: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val hasLogs = state.logs.isNotEmpty()

    LaunchedEffect(Unit) {
        effects.collectLatest { effect ->
            when (effect) {
                is AiLogEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    AppScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.ai_log),
                navigationIcon = { TopBarNavigationButton(onClick = onBackClick) },
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
                contentPadding = adaptiveContentPadding(
                    top = paddingValues.calculateTopPadding(),
                    bottom = 120.dp,
                )
            ) {
                items(state.logs, key = { it.timeText + it.kind + it.provider + it.model }) { item ->
                    LogCard(item)
                }
            }
        }
    }
}

@Composable
private fun LogCard(item: AiLogItemUi) {
    val statusColor = if (item.success) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
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
                text = "${item.provider} / ${item.model}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (item.summary.isNotBlank()) {
                Text(
                    text = item.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 4,
                )
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
                )
            }
        }
    }
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
