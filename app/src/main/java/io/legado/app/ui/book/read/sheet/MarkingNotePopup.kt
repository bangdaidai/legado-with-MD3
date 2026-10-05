package io.legado.app.ui.book.read.sheet

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.legado.app.feature.reader.core.selection.ReaderSelectionMenuAnchor
import io.legado.app.ui.book.read.TextMenuPositionProvider
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.ProvideAppDensity
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.text.AppText

/**
 * 正文里的**只读**备注浮窗：点划线末字右下角的笔记角标打开，把备注摊开看一眼。
 *
 * 两级入口，与笔记弹层（`MarkingSheet`）分工明确：
 * - 点**角标**或浮窗外的正文 → 这里，只读，不带样式/备注控件，也不落库；
 * - 点浮窗里的**备注文本** → 直接进笔记弹层改线型/颜色/备注（[onEditNote]），
 *   锚点仍是这个角标，弹层贴着角标浮出来，改样式时正文里的实时预览不被挡住。
 *
 * 因此它比笔记弹层窄得多，也不抢焦点做输入。
 *
 * 定位复用划词菜单的 [TextMenuPositionProvider]（角标锚点由画布按角标矩形合成），
 * 于是「有上方空间就贴角标上沿向上展开」这条落位规则与划词菜单、笔记弹层完全一致，
 * 三个浮层在同一条划线上互相切换时不会跳位。
 */
@Composable
fun MarkingNotePopup(
    anchor: ReaderSelectionMenuAnchor,
    note: String,
    onEditNote: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val density = LocalDensity.current
    val shadowPadding = 12.dp
    val windowSize = LocalWindowInfo.current.containerSize
    // 比笔记弹层窄：这是一次「扫一眼」，不是要在这里写东西。
    val popupWidth = with(density) { windowSize.width.toDp() } * 0.72f
    val popupMaxHeight = with(density) { (windowSize.height * 0.4f).toDp() }
    val positionProvider = remember(anchor, density.density) {
        TextMenuPositionProvider(
            density = density.density,
            startX = anchor.startX.toInt(),
            startTopY = anchor.startTopY.toInt(),
            startBottomY = anchor.startBottomY.toInt(),
            endX = anchor.endX.toInt(),
            endBottomY = anchor.endBottomY.toInt(),
            shadowPadding = with(density) { shadowPadding.roundToPx() },
        )
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            // 备注是纯文本，不需要键盘；但要能接返回键与「点面板外关闭」。
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        ProvideAppDensity {
            Box(modifier = Modifier.padding(shadowPadding)) {
                NormalCard(
                    modifier = Modifier
                        .width(popupWidth)
                        // 备注文本整块可点：点它进笔记弹层改线型/颜色/备注。role=Button
                        // 让读屏念成"按钮"而不是一段纯文本，否则这条入口对键盘/读屏用户
                        // 根本不存在。
                        .clickable(onClick = onEditNote)
                        .semantics { role = Role.Button },
                    containerColor = LegadoTheme.colorScheme.surfaceBright,
                    elevation = 12.dp,
                    cornerRadius = 12.dp,
                ) {
                    AppText(
                        text = note,
                        style = LegadoTheme.typography.bodyMedium,
                        color = LegadoTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            // 超长备注在浮窗里内部滚动，不把浮窗撑出屏幕。
                            .heightIn(max = popupMaxHeight)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}