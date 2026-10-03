package io.legado.app.ui.widget.components.text


import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import io.legado.app.ui.theme.LegadoTheme

@Composable
fun AppText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    /**
     * 只支持 [TextAlign.Justify] 的词间拉伸。
     *
     * 中文没有空格，词间拉伸量为 0，所以两端对齐在中文上看不到效果；Compose
     * 没有公开 Android `StaticLayout` 的 inter-character justification 入口
     * （1.12.1 / 1.13.0-alpha03 都没有），不要在这里补一个不存在的
     * `justificationMode` 参数——真要按字距拉伸只能自己换掉排版实现。
     */
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle? = null,
) {
    val baseStyle = style ?: LegadoTheme.typography.bodyMedium

    val defaultTextColor = LegadoTheme.colorScheme.onSurface

    val finalTextColor = color.takeOrElse {
        baseStyle.color.takeOrElse { defaultTextColor }
    }

    BasicText(
        text = text,
        modifier = modifier,
        style = baseStyle.merge(
            color = finalTextColor,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign ?: TextAlign.Unspecified,
            lineHeight = lineHeight,
            fontFamily = fontFamily,
            textDecoration = textDecoration,
            fontStyle = fontStyle,
            letterSpacing = letterSpacing,
        ),
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines
    )
}

@Composable
fun AppText(
    text: AnnotatedString, // 接收 AnnotatedString
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    /** 见 [String] 重载的同名参数。 */
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle? = null,
) {
    // 1. 获取基础样式：如果外部没传 style，直接拿你自己封装的 LegadoTheme.typography.bodyMedium
    // 这完美避开了 M3 的 LocalTextStyle，且自动适配 Miuix/M3 引擎！
    val baseStyle = style ?: LegadoTheme.typography.bodyMedium

    // 2. 获取默认文本色：直接拿你自己封装的 LegadoTheme.colorScheme.onSurface
    val defaultTextColor = LegadoTheme.colorScheme.onSurface

    // 3. 颜色降级逻辑：传入的 color -> style 中的 color -> 主题默认色
    val finalTextColor = color.takeOrElse {
        baseStyle.color.takeOrElse { defaultTextColor }
    }

    BasicText(
        text = text,
        modifier = modifier,
        style = baseStyle.merge(
            color = finalTextColor,
            fontSize = fontSize,
            fontWeight = fontWeight,
            textAlign = textAlign ?: TextAlign.Unspecified,
            lineHeight = lineHeight,
            fontFamily = fontFamily,
            textDecoration = textDecoration,
            fontStyle = fontStyle,
            letterSpacing = letterSpacing,
        ),
        onTextLayout = onTextLayout,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines
    )
}