package io.legado.app.ui.association

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.AppIconButton
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

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

    // 跳转确认，禁用源/删除源放入标题栏 ⋮ 溢出菜单
    AppAlertDialog(
        show = true,
        onDismissRequest = { onIntent(OpenUrlConfirmIntent.Cancel) },
        title = stringResource(R.string.open_url_confirm_title),
        text = stringResource(R.string.open_url_confirm_message, state.sourceName),
        titleAction = { SourceOverflowMenu(onIntent) },
        confirmText = stringResource(R.string.ok),
        onConfirm = { onIntent(OpenUrlConfirmIntent.ConfirmOpen) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(OpenUrlConfirmIntent.Cancel) },
    )

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
