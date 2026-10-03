package io.legado.app.ui.widget.components.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.feature.reader.core.style.READER_DOUBLE_LINE_GAP_DP
import io.legado.app.feature.reader.core.style.READER_HALF_HIGHLIGHT_TOP_RATIO
import io.legado.app.feature.reader.core.style.READER_STRIKE_HEIGHT_RATIO
import io.legado.app.feature.reader.core.style.READER_SVG_BASE_WIDTH
import io.legado.app.feature.reader.core.style.READER_SVG_BASELINE_Y
import io.legado.app.feature.reader.core.style.READER_WAVE_CONTROL_OFFSET_DP
import io.legado.app.feature.reader.core.style.READER_WAVE_HALF_WAVE_DP
import io.legado.app.feature.reader.core.style.featherBandPasses
import io.legado.app.feature.reader.core.style.featherGaussian
import io.legado.app.feature.reader.core.style.featherPassCount
import io.legado.app.feature.reader.core.style.scaledDashSegments
import io.legado.app.feature.reader.core.style.waveHalfWaves
import io.legado.app.feature.reader.platform.ReaderSvgPathCache
import io.legado.app.ui.theme.LegadoTheme

/**
 * 笔记（划线/高亮）在普通文本上的表现参数。
 *
 * 数据来自 [TextProcessStyle]（`book_marks.styleJson`），字段含义与正文排版一致：
 * `underlineMode` 用同一套线型编号，线宽/偏移单位都是 dp。划线、荧光色带、
 * 背景色、字体色四类效果都在这里表达，不额外引入「笔记类型」枚举。
 *
 * 颜色一律不做夜间明度反相：正文 `LegacyReaderStyleRangeMapper.resolveModeColor`
 * 的反相依赖阅读器纸张背景与阅读器夜间开关，列表卡片没有同一语境；而用户选色
 * 时看到的预览（`HighlightRulePreview`）是原色直接显示，这里跟预览保持一致，
 * 否则会变成「选的时候这样、列表里那样」。
 */
@Stable
data class MarkingTextDecoration(
    val textColor: Color? = null,
    val backgroundColor: Color? = null,
    val underlineMode: Int = 0,
    val underlineColor: Color? = null,
    val underlineWidth: Float = 1f,
    val underlineOffset: Float = 2f,
    val underlineSvgPath: String = "",
    val underlineRoundCap: Boolean = false,
    val underlineFeather: Float = 0f,
) {
    /** 没有任何可画的效果时按纯文本渲染，跳过排版结果回调与绘制。 */
    val hasDecoration: Boolean
        get() = underlineMode > 0 || textColor != null || backgroundColor != null
}

/**
 * 把持久化的笔记样式映射成表现参数。
 *
 * JSON 解析留给调用方（ViewModel），这里只做字段搬运，UI 组件不碰 GSON / Room。
 */
fun TextProcessStyle.toMarkingTextDecoration(): MarkingTextDecoration = MarkingTextDecoration(
    textColor = textColor?.let { Color(it) },
    backgroundColor = bgColor?.let { Color(it) },
    underlineMode = underlineMode,
    underlineColor = underlineColor?.let { Color(it) },
    underlineWidth = underlineWidth,
    underlineOffset = underlineOffset,
    underlineRoundCap = false,
    underlineFeather = 0f,
    underlineSvgPath = underlineSvgPath.orEmpty(),
)

/**
 * 按笔记样式渲染一段文本。
 *
 * 目标：列表里这段原文的划线观感与正文里那条笔记一致。为此
 * - 线型、线色、几何全部走 [io.legado.app.feature.reader.core.style.ReaderUnderlineGeometry]
 *   与正文/高亮规则预览共用的笔形函数（[drawUnderlineShape]、[drawFluorescentBand]）；
 * - 绘制顺序与正文相同：荧光（`underlineMode == 7`）是填充色带，压在文字之下；
 *   其余是描边，画在文字之上；删除线（6）固定落在行高 [READER_STRIKE_HEIGHT_RATIO]
 *   处，不吃偏移参数。
 *
 * 字体、字号、行高、截断行为则完全交给 [AppText]，因此同一段文字在列表里与正文里
 * 的字面一致，只有划线装饰是这里补上的。
 *
 * 适合笔记列表这类需要「保留划线观感」的短文本。不要用它渲染大段正文：它的排版
 * 完全取决于调用方传给 [AppText] 的参数，不做任何分页与断行优化。
 */
@Composable
fun MarkingStyledText(
    text: String,
    modifier: Modifier = Modifier,
    decoration: MarkingTextDecoration? = null,
    color: Color = Color.Unspecified,
    style: TextStyle? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val textColor = decoration?.textColor
    val backgroundColor = decoration?.backgroundColor
    val underlineMode = decoration?.underlineMode ?: 0
    // 线色兜底跟随字色，与正文 `underlineColor ?: textColor ?: 正文色` 同序；
    // 最后一级用主题正文色而不是阅读器配置——列表不在阅读器语境里。
    val lineColor = decoration?.underlineColor ?: textColor ?: LegadoTheme.colorScheme.onSurface

    val annotated = remember(text, textColor, backgroundColor) {
        buildAnnotatedString {
            append(text)
            if (text.isNotEmpty() && (textColor != null || backgroundColor != null)) {
                addStyle(
                    SpanStyle(
                        color = textColor ?: Color.Unspecified,
                        background = backgroundColor ?: Color.Unspecified,
                    ),
                    0,
                    text.length
                )
            }
        }
    }

    if (decoration == null || !decoration.hasDecoration) {
        AppText(
            text = annotated,
            modifier = modifier,
            color = color,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
        )
        return
    }

    // 排版结果由 AppText 在 measure 阶段回调给出，同帧的 draw 之前必然就绪；
    // 这里只在 draw 阶段读取它，装饰变化不会引起重组。
    val layoutState = remember { mutableStateOf<TextLayoutResult?>(null) }
    AppText(
        text = annotated,
        modifier = modifier.drawWithContent {
            val result = layoutState.value
            if (result == null) {
                drawContent()
            } else {
                val isFluorescent = underlineMode == 7
                if (isFluorescent) {
                    // 荧光恒在文字之下，与正文 ReaderPageDecorationDrawCache 的 halfHighlights 同序
                    drawFluorescentMarks(
                        result = result,
                        textLength = text.length,
                        color = lineColor,
                        roundCap = decoration?.underlineRoundCap == true,
                        feather = decoration?.underlineFeather ?: 0f,
                    )
                }
                drawContent()
                if (!isFluorescent) {
                    drawMarkingUnderlines(
                        result = result,
                        textLength = text.length,
                        mode = underlineMode,
                        color = lineColor,
                        widthDp = decoration?.underlineWidth ?: 1f,
                        offsetDp = decoration?.underlineOffset ?: 2f,
                        svgPath = decoration?.underlineSvgPath.orEmpty(),
                    )
                }
            }
        },
        color = color,
        style = style,
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layoutState.value = it },
    )
}

/** 荧光（`underlineMode == 7`）：铺下半行的填充色带，按行盒切开逐行画。 */
private fun DrawScope.drawFluorescentMarks(
    result: TextLayoutResult,
    textLength: Int,
    color: Color,
    roundCap: Boolean,
    feather: Float,
) {
    result.forEachLineSegment(0, textLength) { left, right, top, bottom, _ ->
        drawFluorescentBand(
            left = left,
            right = right,
            top = top,
            bottom = bottom,
            color = color,
            roundCap = roundCap,
            feather = feather,
        )
    }
}

/**
 * 下划线类效果（1 实线 / 2 虚线 / 3 波浪 / 4 双线 / 5 自定义 SVG / 6 删除线），按行切开逐段画。
 * 删除线固定落在行高 [READER_STRIKE_HEIGHT_RATIO] 处，其余落在行底 + 偏移。
 */
private fun DrawScope.drawMarkingUnderlines(
    result: TextLayoutResult,
    textLength: Int,
    mode: Int,
    color: Color,
    widthDp: Float,
    offsetDp: Float,
    svgPath: String,
) {
    if (mode <= 0) return
    result.forEachLineSegment(0, textLength) { left, right, top, bottom, _ ->
        if (mode == 6) {
            drawUnderlineSegment(
                mode = 1,
                color = color,
                widthDp = widthDp,
                startX = left,
                endX = right,
                y = top + (bottom - top) * READER_STRIKE_HEIGHT_RATIO,
            )
        } else {
            drawUnderlineSegment(
                mode = mode,
                color = color,
                widthDp = widthDp,
                startX = left,
                endX = right,
                y = bottom + offsetDp.dp.toPx(),
                svgPath = svgPath,
            )
        }
    }
}

/**
 * 荧光色带：铺满行盒下半行的填充矩形，不是描边。
 *
 * 与正文 `ReaderHalfHighlightDrawCommand` 同一份几何（矩形范围、圆头半径、
 * 柔化趟次都来自 `ReaderUnderlineGeometry`）。柔化是「同矩形多层 alpha 叠加」
 * 模拟模糊：色带范围不缩小，边界靠实色逐渐化开到透明。
 */
internal fun DrawScope.drawFluorescentBand(
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    color: Color,
    roundCap: Boolean,
    feather: Float,
) {
    val bandTop = top + (bottom - top) * READER_HALF_HIGHLIGHT_TOP_RATIO
    val bandHeight = (bottom - bandTop).coerceAtLeast(0f)
    val radius = if (roundCap) bandHeight / 2f else 0f
    val baseAlpha = color.alpha
    // 模拟模糊：每趟都画满同一个矩形，靠 alpha 叠加让边界化开。
    // 刻意不做「向内收缩」——那会让色带整体变小、上边缘与文字裂开一条缝，
    // 边界反而更生硬。
    featherBandPasses(feather).forEach { pass ->
        val alpha = baseAlpha * pass.alphaScale
        if (alpha <= 0.001f) return@forEach
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(left, bandTop),
            size = Size(right - left, bandHeight),
            cornerRadius = CornerRadius(radius, radius),
        )
    }
}

/**
 * 把匹配区间按行切开，用每字包围盒算这一行的左右边界。
 * [TextLayoutResult.getHorizontalPosition] 在换行边界会给出下一行行首，
 * 跨行引用会把下划线/背景图画到行首未匹配文字上。
 */
internal fun TextLayoutResult.forEachLineSegment(
    start: Int,
    endExclusive: Int,
    action: (left: Float, right: Float, top: Float, bottom: Float, line: Int) -> Unit,
) {
    if (start >= endExclusive) return
    var offset = start
    while (offset < endExclusive) {
        val line = getLineForOffset(offset)
        val visibleEnd = getLineEnd(line, visibleEnd = true)
        val rawEnd = getLineEnd(line, visibleEnd = false)
        val segEnd = minOf(endExclusive, visibleEnd)
        if (segEnd > offset) {
            var left = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            for (i in offset until segEnd) {
                val box = getBoundingBox(i)
                left = minOf(left, box.left)
                right = maxOf(right, box.right)
            }
            if (left < right) {
                action(left, right, getLineTop(line), getLineBottom(line), line)
            }
        }
        // 软换行时 visibleEnd==rawEnd；硬换行时 visibleEnd 停在 \n 前，必须跳过否则死循环
        offset = maxOf(segEnd, rawEnd).coerceAtLeast(offset + 1)
    }
}

internal fun DrawScope.drawUnderlineSegment(
    mode: Int,
    color: Color,
    widthDp: Float,
    startX: Float,
    endX: Float,
    y: Float,
    roundCap: Boolean = false,
    feather: Float = 0f,
    dashLen: Float = 8f,
    dashGap: Float = 5f,
    svgPath: String = "",
    wavePeakDp: Float = READER_WAVE_CONTROL_OFFSET_DP / 2f,
    waveLengthDp: Float = READER_WAVE_HALF_WAVE_DP * 2f,
) {
    val cap = if (roundCap || feather > 0f) StrokeCap.Round else StrokeCap.Butt
    // 线芯宽度在这里换算并抬到 1px，与正文 drawShape 的
    // strokeWidth.coerceAtLeast(1f) 同口径：亚像素线宽正文会抬到 1px，
    // 预览不抬的话两侧粗细不同
    val coreWidth = widthDp.dp.toPx().coerceAtLeast(1f)
    // 圆头内缩只按线芯宽度：羽化 pass 的加粗圆头向外扩散，内缩若随加粗增大，
    // 两端渐隐区会被整段吃掉，羽化和圆头一起失效
    val capInset = if (cap == StrokeCap.Round) coreWidth / 2f else 0f
    if (feather > 0f) {
        val passes = featherPassCount(feather)
        val baseAlpha = color.alpha
        val featherPx = feather.dp.toPx()
        // 端部渐隐长度：至少覆盖羽化扩散半径与线宽
        val featherLen = maxOf(featherPx * 1.5f, coreWidth)
        val segLen = endX - startX
        val edgePos = if (segLen > 0f) (featherLen / segLen).coerceIn(0f, 0.5f) else 0.5f
        for (i in passes downTo 0) {
            val d = i.toFloat() / passes
            val alpha = baseAlpha * featherGaussian(d)
            if (alpha <= 0.001f) continue
            val passColor = color.copy(alpha = alpha)
            val passWidth = coreWidth + d * featherPx * 2f
            // 端点水平渐隐：两端 alpha 渐变为 0
            val brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    edgePos to passColor,
                    1f - edgePos to passColor,
                    1f to Color.Transparent,
                ),
                start = Offset(startX, 0f),
                end = Offset(endX, 0f),
            )
            drawUnderlineShape(
                mode = mode, color = passColor, strokeWidth = passWidth,
                startX = startX, endX = endX, y = y, cap = cap, capInset = capInset,
                brush = brush, dashLen = dashLen, dashGap = dashGap, svgPath = svgPath,
                wavePeakDp = wavePeakDp, waveLengthDp = waveLengthDp,
            )
        }
    } else {
        drawUnderlineShape(
            mode = mode, color = color, strokeWidth = coreWidth,
            startX = startX, endX = endX, y = y, cap = cap, capInset = capInset,
            dashLen = dashLen, dashGap = dashGap, svgPath = svgPath,
            wavePeakDp = wavePeakDp, waveLengthDp = waveLengthDp,
        )
    }
}

/**
 * 各线型的实际笔形。几何常量全部来自
 * [io.legado.app.feature.reader.core.style.ReaderUnderlineGeometry]，与正文
 * `ReaderUnderlineDrawCommand` 同一份来源，绘制代码不再自带一套数字。
 */
internal fun DrawScope.drawUnderlineShape(
    mode: Int,
    color: Color,
    strokeWidth: Float,
    startX: Float,
    endX: Float,
    y: Float,
    cap: StrokeCap = StrokeCap.Butt,
    capInset: Float = 0f,
    brush: Brush? = null,
    dashLen: Float = 8f,
    dashGap: Float = 5f,
    svgPath: String = "",
    wavePeakDp: Float = READER_WAVE_CONTROL_OFFSET_DP / 2f,
    waveLengthDp: Float = READER_WAVE_HALF_WAVE_DP * 2f,
) {
    val sx = startX + capInset
    val ex = endX - capInset
    if (sx >= ex) return
    val solidBrush = brush ?: Brush.linearGradient(listOf(color, color))
    when (mode) {
        1 -> drawLine(
            brush = solidBrush,
            start = Offset(sx, y),
            end = Offset(ex, y),
            strokeWidth = strokeWidth,
            cap = cap,
        )

        2 -> {
            // 与正文 drawDashed 同一套周期均摊算法，段尾不再被截出碎段
            val (periods, on, off) = scaledDashSegments(
                ex - sx,
                dashLen.dp.toPx(),
                dashGap.dp.toPx(),
            )
            for (i in 0 until periods) {
                val segStart = sx + i * (on + off)
                if (segStart >= ex) break
                drawLine(
                    brush = solidBrush,
                    start = Offset(segStart, y),
                    end = Offset((segStart + on).coerceAtMost(ex), y),
                    strokeWidth = strokeWidth,
                    cap = cap,
                )
            }
        }

        3 -> {
            // 半波节点来自共享几何：余数摊匀、末段强制收口到 ex，
            // 与正文/选中预览同一份疏密。注意传入的是半波长（整波长的一半）
            // 与控制点偏移（= 峰高的 2 倍，二次贝塞尔中点只到控制点的一半）。
            val halfWaves = waveHalfWaves(
                sx,
                ex,
                (waveLengthDp / 2f).dp.toPx(),
                (wavePeakDp * 2f).dp.toPx(),
            )
            if (halfWaves.isEmpty()) return
            val path = Path().apply {
                moveTo(halfWaves.first().startX, y)
                halfWaves.forEach { wave ->
                    quadraticTo(
                        (wave.startX + wave.endX) / 2f,
                        y + wave.controlOffsetY,
                        wave.endX,
                        y,
                    )
                }
            }
            drawPath(
                path = path,
                brush = solidBrush,
                style = Stroke(width = strokeWidth, cap = cap),
            )
        }

        4 -> {
            // 双线全部画在基线下方：第二条 = y + 净间隙 + 线宽（与正文同），
            // 不是围绕 y 上下对称
            val secondY = y + READER_DOUBLE_LINE_GAP_DP.dp.toPx() + strokeWidth
            drawLine(
                brush = solidBrush,
                start = Offset(sx, y),
                end = Offset(ex, y),
                strokeWidth = strokeWidth,
                cap = cap,
            )
            drawLine(
                brush = solidBrush,
                start = Offset(sx, secondY),
                end = Offset(ex, secondY),
                strokeWidth = strokeWidth,
                cap = cap,
            )
        }

        5 -> {
            // 自定义 SVG：与正文同一套变换（按 viewBox 宽横向拉伸、基线对齐到 y）。
            // 正文用的是整段 bounds 而不是圆头内缩后的区间，这里把内缩加回去，
            // 否则开了圆头/羽化时画出来的 SVG 比正文窄半个线宽。
            val svgLeft = sx - capInset
            val svgRight = ex + capInset
            val path = ReaderSvgPathCache.parse(svgPath)?.asAndroidPath() ?: return
            withTransform({
                translate(Offset(svgLeft, y - READER_SVG_BASELINE_Y))
                scale(scaleX = (svgRight - svgLeft) / READER_SVG_BASE_WIDTH, scaleY = 1f, pivot = Offset.Zero)
            }) {
                drawPath(path = path, brush = solidBrush, style = Stroke(width = strokeWidth, cap = cap))
            }
        }
    }
}
