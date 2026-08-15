package io.legado.app.ui.book.read.sheet

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
import io.legado.app.ui.book.read.DefaultMarkingStyle
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

    // 荧光笔的粗细/偏移是全局设置（不进 styleJson）：所有荧光笔标记共用一套，
    // 拖动即写入，渲染时统一覆盖，已有的荧光笔标记也跟着变。
    var highlighterWidth by remember(show) {
        mutableFloatStateOf(DefaultMarkingStyle.highlighterWidth)
    }
    var highlighterOffset by remember(show) {
        mutableFloatStateOf(DefaultMarkingStyle.highlighterOffset)
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

            // 渲染不认这里存的粗细/偏移（走全局值），但仍要写进去：
            // MarkingEffect.fromStyle 靠线宽 ≥ 阈值把它认成荧光笔而不是普通单实线。
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
        Column(modifier = Modifier.fillMaxWidth()) {
            // 各段间距统一 12dp。不用 verticalArrangement：颜色行自带 8dp 纵向内边距、
            // 设置卡自带 4dp 下边距，各元素自带的边距不一样，统一行距反而对不齐，
            // 所以按「12dp 减去元素自带的那部分」显式补 Spacer。
            MarkingColorRow(
                selectedColor = markColor,
                onColorSelected = { markColor = it },
                onCustomColorClick = { showColorPicker = true },
            )
            Spacer(Modifier.height(4.dp)) // 颜色行自带 8dp
            MarkingEffectGrid(
                selectedEffect = effect,
                onEffectSelected = { effect = it },
            )
            Spacer(Modifier.height(12.dp)) // 效果格无自带边距
            // 只有荧光笔露出粗细/偏移滑块。这两项是全局的：拖动即写入偏好，
            // 保存后所有荧光笔标记（含已有的）一起按新值渲染。
            if (effect == MarkingEffect.HIGHLIGHTER) {
                TinySliderSettingItem(
                    title = stringResource(R.string.underline_width),
                    value = highlighterWidth,
                    // 下限贴着荧光笔判定阈值，再细保存后会被反推成普通实线
                    valueRange = MarkingEffect.HIGHLIGHTER_WIDTH_MIN..24f,
                    description = String.format("%.1f dp", highlighterWidth),
                    onValueChange = {
                        highlighterWidth = (it * 10).toInt() / 10f
                        DefaultMarkingStyle.highlighterWidth = highlighterWidth
                    },
                    onReset = {
                        highlighterWidth = MarkingEffect.HIGHLIGHTER_WIDTH
                        DefaultMarkingStyle.highlighterWidth = highlighterWidth
                    },
                )
                Spacer(Modifier.height(8.dp)) // 设置卡自带 4dp
                TinySliderSettingItem(
                    title = stringResource(R.string.underline_offset),
                    value = highlighterOffset,
                    // 负值才是荧光笔：把色带从行底抬进文字里
                    valueRange = -20f..0f,
                    description = String.format("%+.1f dp", highlighterOffset),
                    onValueChange = {
                        highlighterOffset = (it * 10).toInt() / 10f
                        DefaultMarkingStyle.highlighterOffset = highlighterOffset
                    },
                    onReset = {
                        highlighterOffset = MarkingEffect.HIGHLIGHTER_OFFSET
                        DefaultMarkingStyle.highlighterOffset = highlighterOffset
                    },
                )
                Spacer(Modifier.height(8.dp)) // 设置卡自带 4dp
            }
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
            Spacer(Modifier.height(12.dp)) // 收尾留白，与各段间距一致
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
