package io.legado.app.ui.book.read.sheet

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
 * 正文里的**只读**备注浮窗：点划线末行右下角的笔记角标打开，把备注摊开看一眼。
 *
 * 与笔记弹层（`MarkingSheet`）分工明确：这里只读，不带样式/备注控件，也不落库；
 * 想改仍然点划线原文进笔记弹层。因此它比笔记弹层窄得多，也不抢焦点做输入。
 *
 * 定位复用划词菜单的 [TextMenuPositionProvider]（角标锚点由画布按角标矩形合成），
 * 于是「有上方空间就贴角标上沿向上展开」这条落位规则与划词菜单、笔记弹层完全一致，
 * 三个浮层在同一条划线上互相切换时不会跳位。
 */
@Composable
fun MarkingNotePopup(
    anchor: ReaderSelectionMenuAnchor,
    note: String,
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
                    modifier = Modifier.width(popupWidth),
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