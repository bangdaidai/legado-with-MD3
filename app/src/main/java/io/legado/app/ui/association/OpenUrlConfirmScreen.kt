package io.legado.app.ui.association

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.LegadoTheme.composeEngine
import io.legado.app.ui.theme.ProvideAppDensity
import io.legado.app.ui.theme.ThemeResolver
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.AppIconButton
import io.legado.app.ui.widget.components.button.PrimaryButton
import io.legado.app.ui.widget.components.button.SecondaryButton
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun OpenUrlConfirmScreen(
    state: OpenUrlConfirmUiState,
    onIntent: (OpenUrlConfirmIntent) -> Unit,
    effects: Flow<OpenUrlConfirmEffect>,
    onOpenUrl: (uri: String, mimeType: String?) -> Unit,
    onFinish: () -> Unit,
) {
    LaunchedEffect(Unit) {
        effects.collectLatest { effect ->
            when (effect) {
                is OpenUrlConfirmEffect.OpenUrl -> onOpenUrl(effect.uri, effect.mimeType)
                OpenUrlConfirmEffect.Finish -> onFinish()
            }
        }
    }

    OpenUrlConfirmDialog(state = state, onIntent = onIntent)

    // 删除源二次确认
    AppAlertDialog(
        show = state.showDeleteConfirm,
        onDismissRequest = { onIntent(OpenUrlConfirmIntent.DismissDeleteConfirm) },
        title = stringResource(R.string.draw),
        text = stringResource(R.string.sure_del) + "\n" + state.sourceName,
        confirmText = stringResource(R.string.ok),
        onConfirm = { onIntent(OpenUrlConfirmIntent.ConfirmDeleteSource) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(OpenUrlConfirmIntent.DismissDeleteConfirm) },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OpenUrlConfirmDialog(
    state: OpenUrlConfirmUiState,
    onIntent: (OpenUrlConfirmIntent) -> Unit,
) {
    val title = stringResource(R.string.open_url_confirm_title)
    val message = stringResource(R.string.open_url_confirm_message, state.sourceName)
    val confirmText = stringResource(R.string.ok)
    val dismissText = stringResource(R.string.cancel)

    if (ThemeResolver.isMiuixEngine(composeEngine)) {
        // Miuix WindowDialog 的 title 只支持 String，溢出菜单放在内容区右上角
        WindowDialog(
            show = true,
            title = title,
            summary = message,
            onDismissRequest = { onIntent(OpenUrlConfirmIntent.Cancel) },
            content = {
                ProvideAppDensity {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        SourceOverflowMenu(onIntent)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SecondaryButton(
                            text = dismissText,
                            modifier = Modifier.weight(1f),
                            onClick = { onIntent(OpenUrlConfirmIntent.Cancel) },
                        )
                        PrimaryButton(
                            text = confirmText,
                            modifier = Modifier.weight(1f),
                            onClick = { onIntent(OpenUrlConfirmIntent.ConfirmOpen) },
                        )
                    }
                }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = { onIntent(OpenUrlConfirmIntent.Cancel) },
            containerColor = LegadoTheme.colorScheme.surfaceContainer,
            iconContentColor = LegadoTheme.colorScheme.primary,
            titleContentColor = LegadoTheme.colorScheme.onSurface,
            textContentColor = LegadoTheme.colorScheme.onSurfaceVariant,
            tonalElevation = AlertDialogDefaults.TonalElevation,
            title = {
                ProvideAppDensity {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                        )
                        SourceOverflowMenu(onIntent)
                    }
                }
            },
            text = {
                ProvideAppDensity {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(text = message)
                        }
                    }
                }
            },
            confirmButton = {
                ProvideAppDensity {
                    PrimaryButton(
                        text = confirmText,
                        onClick = { onIntent(OpenUrlConfirmIntent.ConfirmOpen) },
                    )
                }
            },
            dismissButton = {
                ProvideAppDensity {
                    SecondaryButton(
                        text = dismissText,
                        onClick = { onIntent(OpenUrlConfirmIntent.Cancel) },
                    )
                }
            },
        )
    }
}

@Composable
private fun SourceOverflowMenu(onIntent: (OpenUrlConfirmIntent) -> Unit) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        AppIconButton(onClick = { showMenu = true }) {
            Icon(
                imageVector = AppIcons.MoreVert,
                contentDescription = stringResource(R.string.more_menu),
            )
        }
        RoundDropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) { dismiss ->
            RoundDropdownMenuItem(
                text = stringResource(R.string.disable_source),
                onClick = {
                    dismiss()
                    onIntent(OpenUrlConfirmIntent.DisableSource)
                }
            )
            RoundDropdownMenuItem(
                text = stringResource(R.string.delete_source),
                onClick = {
                    dismiss()
                    onIntent(OpenUrlConfirmIntent.RequestDeleteSource)
                }
            )
        }
    }
}
