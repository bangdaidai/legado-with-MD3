package io.legado.app.ui.book.read.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.domain.model.MarkingEffect
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.dialog.ColorPickerSheet
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.text.AppText

/**
 * 「笔记默认样式」编辑 Sheet：复用 [MarkingColorRow] + [MarkingEffectGrid]，
 * 不含备注、不含规则复用——这就是点「笔记」时直接套用的独立默认样式。
 * 与「高亮规则」（正则自动高亮）无关。
 */
@Composable
fun DefaultMarkingStyleSheet(
    show: Boolean,
    initialStyle: TextProcessStyle,
    onDismissRequest: () -> Unit,
    onSave: (TextProcessStyle) -> Unit,
) {
    var effect by remember(show) { mutableStateOf(MarkingEffect.fromStyle(initialStyle)) }
    var markColor by remember(show) { mutableStateOf(MarkingEffect.colorOf(initialStyle)) }
    var showColorPicker by remember(show) { mutableStateOf(false) }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.default_marking_style),
        endAction = {
            MediumTonalButton(
                onClick = {
                    val base = effect.toStyle(markColor)
                    // 下划线类沿用旧样式的线宽/偏移/SVG，避免编辑默认时被重置
                    val style = if (effect.isUnderline) {
                        base.copy(
                            underlineWidth = initialStyle.underlineWidth,
                            underlineOffset = initialStyle.underlineOffset,
                            underlineSvgPath = initialStyle.underlineSvgPath,
                        )
                    } else {
                        base
                    }
                    onSave(style)
                },
                icon = Icons.Default.Save,
                contentDescription = stringResource(android.R.string.ok),
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MarkingColorRow(
                selectedColor = markColor,
                onColorSelected = { markColor = it },
                onCustomColorClick = { showColorPicker = true },
            )
            MarkingEffectGrid(
                selectedEffect = effect,
                onEffectSelected = { effect = it },
            )
            AppText(
                text = stringResource(R.string.default_marking_style_hint),
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    ColorPickerSheet(
        show = showColorPicker,
        initialColor = markColor,
        onDismissRequest = { showColorPicker = false },
        onColorSelected = { color ->
            markColor = color
            showColorPicker = false
        },
    )
}
