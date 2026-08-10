package io.legado.app.ui.association

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.ui.widget.components.alert.AppAlertDialog
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

    // 跳转确认
    AppAlertDialog(
        show = true,
        onDismissRequest = { onIntent(OpenUrlConfirmIntent.Cancel) },
        title = stringResource(R.string.open_url_confirm_title),
        text = stringResource(R.string.open_url_confirm_message, state.sourceName),
        confirmText = stringResource(R.string.ok),
        onConfirm = { onIntent(OpenUrlConfirmIntent.ConfirmOpen) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(OpenUrlConfirmIntent.Cancel) },
        content = {
            // 原 Toolbar 溢出菜单中的源管理操作
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
            ) {
                TextButton(onClick = { onIntent(OpenUrlConfirmIntent.DisableSource) }) {
                    Text(text = stringResource(R.string.disable_source))
                }
                TextButton(onClick = { onIntent(OpenUrlConfirmIntent.RequestDeleteSource) }) {
                    Text(text = stringResource(R.string.delete_source))
                }
            }
        },
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
