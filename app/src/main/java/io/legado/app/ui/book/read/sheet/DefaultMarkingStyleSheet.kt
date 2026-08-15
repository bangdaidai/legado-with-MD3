package io.legado.app.ui.book.read.sheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.dialog.ColorPickerSheet
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet

/**
 * 「笔记默认样式」编辑 Sheet：预览 + 颜色行 + 5x1 效果格，
 * 复用 [MarkingColorRow] / [MarkingEffectGrid] 与高亮规则的 [HighlightRulePreview]，
 * 不含备注、不含规则复用——这就是点「笔记」时直接套用的独立默认样式。
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

    // 当前编辑中的样式：预览与保存共用同一份，避免两处推导不一致
    val editingStyle = remember(effect, markColor, initialStyle) {
        val base = effect.toStyle(markColor)
        if (effect.isUnderline) {
            base.copy(
                underlineWidth = initialStyle.underlineWidth,
                underlineOffset = initialStyle.underlineOffset,
                underlineSvgPath = initialStyle.underlineSvgPath,
            )
        } else {
            base
        }
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.default_marking_style),
        endAction = {
            MediumTonalButton(
                onClick = { onSave(editingStyle) },
                icon = Icons.Default.Save,
                contentDescription = stringResource(android.R.string.ok),
            )
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
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
            // 预览卡片放在样式下方。与高亮规则编辑页同一个组件；
            // 笔记没有正则，pattern 用 ".+" 让整段示例都命中。
            HighlightRulePreview(
                label = stringResource(R.string.preview_effect),
                sampleText = stringResource(R.string.default_marking_style_preview),
                pattern = ".+",
                textColor = editingStyle.textColor,
                bgColor = editingStyle.bgColor,
                bgImage = "",
                bgImageFit = 0,
                bgImageScale = 1f,
                underlineMode = editingStyle.underlineMode,
                underlineColor = editingStyle.underlineColor,
                underlineWidth = editingStyle.underlineWidth,
                underlineOffset = editingStyle.underlineOffset,
                // 荧光笔在正文里画在文字层之下，预览也照此，文字压在色带上面
                underlineBelowText = effect == MarkingEffect.HIGHLIGHTER,
                pageBgColor = runCatching {
                    android.graphics.Color.parseColor(ReadBookConfig.durConfig.bgStr)
                }.getOrDefault(0xFFEEEEEE.toInt()),
                pageTextColor = ReadBookConfig.textColor,
                modifier = Modifier.fillMaxWidth(),
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
