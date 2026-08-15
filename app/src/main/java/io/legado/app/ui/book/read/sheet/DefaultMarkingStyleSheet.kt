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
import androidx.compose.runtime.mutableFloatStateOf
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
import io.legado.app.ui.widget.components.settingItem.TinySliderSettingItem

/**
 * 「笔记默认样式」编辑 Sheet：颜色行 + 效果格 + 预览，选中荧光笔时多出粗细/偏移滑块，
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

    // 荧光笔的粗细/偏移可调：已存的就是荧光笔时接着上次的值，否则从效果默认值起步
    val savedHighlighter = MarkingEffect.fromStyle(initialStyle) == MarkingEffect.HIGHLIGHTER
    var highlighterWidth by remember(show) {
        mutableFloatStateOf(
            if (savedHighlighter) initialStyle.underlineWidth else MarkingEffect.HIGHLIGHTER_WIDTH
        )
    }
    var highlighterOffset by remember(show) {
        mutableFloatStateOf(
            if (savedHighlighter) initialStyle.underlineOffset else MarkingEffect.HIGHLIGHTER_OFFSET
        )
    }

    // 当前编辑中的样式：预览与保存共用同一份，避免两处推导不一致
    val editingStyle = remember(
        effect, markColor, initialStyle, highlighterWidth, highlighterOffset
    ) {
        val base = effect.toStyle(markColor)
        when {
            effect.isUnderline -> base.copy(
                underlineWidth = initialStyle.underlineWidth,
                underlineOffset = initialStyle.underlineOffset,
                underlineSvgPath = initialStyle.underlineSvgPath,
            )

            effect == MarkingEffect.HIGHLIGHTER -> base.copy(
                underlineWidth = highlighterWidth,
                underlineOffset = highlighterOffset,
            )

            else -> base
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
            // 颜色行自带 8dp 纵向内边距，这里用 4dp 行距凑成 12dp；
            // 效果格没有自带边距，到预览之间另补 8dp，保持三段间距一致
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
            // 只有荧光笔露出粗细/偏移滑块：其余效果的线宽线偏移沿用旧样式，没有可调项
            if (effect == MarkingEffect.HIGHLIGHTER) {
                TinySliderSettingItem(
                    title = stringResource(R.string.underline_width),
                    value = highlighterWidth,
                    // 下限贴着荧光笔判定阈值，再细保存后会被反推成普通实线
                    valueRange = MarkingEffect.HIGHLIGHTER_WIDTH_MIN..24f,
                    description = String.format("%.1f dp", highlighterWidth),
                    onValueChange = { highlighterWidth = (it * 10).toInt() / 10f },
                    onReset = { highlighterWidth = MarkingEffect.HIGHLIGHTER_WIDTH },
                )
                TinySliderSettingItem(
                    title = stringResource(R.string.underline_offset),
                    value = highlighterOffset,
                    // 负值才是荧光笔：把色带从行底抬进文字里
                    valueRange = -20f..0f,
                    description = String.format("%+.1f dp", highlighterOffset),
                    onValueChange = { highlighterOffset = (it * 10).toInt() / 10f },
                    onReset = { highlighterOffset = MarkingEffect.HIGHLIGHTER_OFFSET },
                )
            }
            Spacer(Modifier.height(8.dp))
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
