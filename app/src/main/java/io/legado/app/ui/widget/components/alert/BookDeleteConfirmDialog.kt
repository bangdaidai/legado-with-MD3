package io.legado.app.ui.widget.components.alert

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.text.AppText

/**
 * 书籍删除确认对话框（书架管理 / 书籍信息页共用）。
 * 操作按钮：删除、删除并弃文；取消通过触摸外部或系统返回触发 [onDismissRequest]。
 */
@Composable
fun BookDeleteConfirmDialog(
    show: Boolean,
    isLocal: Boolean,
    initialDeleteOriginal: Boolean = false,
    onDismissRequest: () -> Unit,
    onDelete: (deleteOriginal: Boolean) -> Unit,
    onDeleteAndAbandon: (deleteOriginal: Boolean) -> Unit,
) {
    var deleteOriginal by remember(show) { mutableStateOf(initialDeleteOriginal) }
    AppAlertDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.delete),
        text = stringResource(R.string.sure_del),
        dismissText = "删除并弃文",
        onDismiss = { onDeleteAndAbandon(deleteOriginal) },
        confirmText = stringResource(R.string.delete),
        onConfirm = { onDelete(deleteOriginal) },
        content = {
            if (isLocal) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = deleteOriginal,
                        onCheckedChange = { deleteOriginal = it },
                        colors = CheckboxDefaults.colors(
                            checkedColor = LegadoTheme.colorScheme.primary,
                            checkmarkColor = LegadoTheme.colorScheme.onPrimary,
                            uncheckedColor = LegadoTheme.colorScheme.onSurfaceVariant,
                        )
                    )
                    AppText(text = stringResource(R.string.delete_book_file))
                }
            }
        },
    )
}
