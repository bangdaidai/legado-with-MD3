package io.legado.app.ui.widget.components.alert

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.LegadoTheme.composeEngine
import io.legado.app.ui.theme.ProvideAppDensity
import io.legado.app.ui.theme.ThemeResolver
import io.legado.app.ui.widget.components.button.PrimaryButton
import io.legado.app.ui.widget.components.button.SecondaryButton
import top.yukonga.miuix.kmp.window.WindowDialog

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppAlertDialog(
    modifier: Modifier = Modifier,
    show: Boolean,
    onDismissRequest: () -> Unit,
    title: String? = null,
    text: String? = null,
    content: (@Composable () -> Unit)? = null,
    /** 标题栏尾部操作槽（如 ⋮ 溢出菜单）。Miuix 引擎的标题不支持自定义槽，退化到内容区右上角。 */
    titleAction: (@Composable () -> Unit)? = null,
    confirmText: String = "确定", // 默认文字
    onConfirm: (() -> Unit)? = null,
    /** 确定按钮是否可点：表单类弹窗里输入还不合法时置 false，而不是点了才弹一句提示。 */
    confirmEnabled: Boolean = true,
    dismissText: String = "取消",
    onDismiss: (() -> Unit)? = null,
    /**
     * 底部动作条最左的附加动作（如「试听」）：取消在它右边，样式与取消同档。
     * 两个槽只够放取消/确定，表单弹窗常有的第三个动作（试听、撤销）从这进，
     * 让动作条整体仍由本组件排布，而不是调用方自己在 content 里画一排按钮。
     */
    startActionText: String? = null,
    onStartAction: (() -> Unit)? = null,
    startActionEnabled: Boolean = true,
) {
    if (ThemeResolver.isMiuixEngine(composeEngine)) {
        WindowDialog(
            show = show,
            modifier = modifier,
            title = title,
            summary = text,
            onDismissRequest = onDismissRequest,
            content = {
                ProvideAppDensity {
                    if (titleAction != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            titleAction()
                        }
                    }
                    if (content != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            content()
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onStartAction != null && startActionText != null) {
                            SecondaryButton(
                                text = startActionText,
                                modifier = Modifier.weight(1f),
                                enabled = startActionEnabled,
                                onClick = {
                                    onStartAction()
                                }
                            )
                        }

                        if (onDismiss != null) {
                            SecondaryButton(
                                text = dismissText,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    onDismiss()
                                }
                            )
                        }

                        if (onConfirm != null) {
                            PrimaryButton(
                                text = confirmText,
                                modifier = Modifier.weight(1f),
                                enabled = confirmEnabled,
                                onClick = {
                                    onConfirm()
                                }
                            )
                        }
                    }
                }
            }
        )
    } else {
        if (show) {
            AlertDialog(
                onDismissRequest = onDismissRequest,
                modifier = modifier,
                containerColor = LegadoTheme.colorScheme.surfaceContainer,
                iconContentColor = LegadoTheme.colorScheme.primary,
                titleContentColor = LegadoTheme.colorScheme.onSurface,
                textContentColor = LegadoTheme.colorScheme.onSurfaceVariant,
                tonalElevation = AlertDialogDefaults.TonalElevation,
                title = title?.let {
                    {
                        ProvideAppDensity {
                            if (titleAction != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = it, modifier = Modifier.weight(1f))
                                    titleAction()
                                }
                            } else {
                                Text(text = it)
                            }
                        }
                    }
                },
                text = {
                    ProvideAppDensity {
                        Column(
                            modifier = Modifier.verticalScroll(rememberScrollState())
                        ) {
                            if (text != null) {
                                SelectionContainer {
                                    Text(
                                        text = text,
                                        modifier = Modifier.padding(bottom = if (content != null) 16.dp else 0.dp)
                                    )
                                }
                            }
                            if (content != null) {
                                content()
                            }
                        }
                    }
                },
                confirmButton = {
                    if (onConfirm != null) {
                        ProvideAppDensity {
                            PrimaryButton(
                                onClick = onConfirm,
                                enabled = confirmEnabled,
                                text = confirmText
                            )
                        }
                    }
                },
                dismissButton = {
                    ProvideAppDensity {
                        // M3 只给确认/取消两个槽：附加动作并入取消这侧，
                        // flow row 会把它与确认排进同一排（右对齐，按内容宽）
                        if (onStartAction != null && startActionText != null) {
                            SecondaryButton(
                                onClick = { onStartAction() },
                                enabled = startActionEnabled,
                                text = startActionText
                            )
                        }
                        if (onDismiss != null) {
                            SecondaryButton(
                                onClick = {
                                    onDismiss()
                                },
                                text = dismissText
                            )
                        }
                    }
                }
            )
        }
    }
}

/**
 * 专为 nullable 数据设计的 AppAlertDialog 重载。
 * 当 [data] 不为 null 时显示弹窗；当 [data] 变为 null 时，自动缓存最后一次数据并播放退出动画。
 */
@Composable
fun <T> AppAlertDialog(
    data: T?,
    onDismissRequest: () -> Unit,
    title: String? = null,
    text: String? = null,
    textProvider: @Composable (T.() -> String)? = null,
    confirmText: String = "确定",
    onConfirm: ((T) -> Unit)? = null,
    confirmEnabled: Boolean = true,
    dismissText: String = "取消",
    onDismiss: (() -> Unit)? = null,
    startActionText: String? = null,
    onStartAction: (() -> Unit)? = null,
    startActionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: (@Composable (T) -> Unit)? = null
) {
    var cachedData by remember { mutableStateOf(data) }

    if (data != null) {
        cachedData = data
    }

    val currentData = cachedData
    if (currentData != null) {
        val currentText = text ?: textProvider?.invoke(currentData)
        var lastValidText by remember { mutableStateOf(currentText) }
        
        if (currentText != null) {
            lastValidText = currentText
        }

        AppAlertDialog(
            show = data != null,
            onDismissRequest = onDismissRequest,
            title = title,
            text = currentText ?: lastValidText,
            modifier = modifier,
            confirmText = confirmText,
            onConfirm = onConfirm?.let { { it(currentData) } },
            confirmEnabled = confirmEnabled,
            dismissText = dismissText,
            onDismiss = onDismiss,
            startActionText = startActionText,
            onStartAction = onStartAction,
            startActionEnabled = startActionEnabled,
            content = content?.let { { it(currentData) } }
        )
    }
}
