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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathCompat
import androidx.core.graphics.PathIterator
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
import io.legado.app.utils.ColorUtils

/**
 * 笔记（划线/高亮）在普通文本上的表现参数。
 *
 * 数据来自 [TextProcessStyle]（`book_marks.styleJson`），字段含义与正文排版一致：
 * `underlineMode` 用同一套线型编号，线宽/偏移单位都是 dp。划线、荧光色带、
 * 背景色、字体色四类效果都在这里表达，不额外引入「笔记类型」枚举。
 *
 * 颜色字段存的是**日间原色**（不做反相），夜间派生在 [MarkingStyledText] 渲染时按
 * [LegadoTheme.isDark] 现算，与正文 `LegacyReaderStyleRangeMapper.resolveModeColor`
 * 同一语义。这样主题切换不需要重新解析 JSON。
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
    // 笔记样式没有夜间色，夜间按明度反相派生，与正文 resolveModeColor 同一语义。
    // 判定用应用主题而不是阅读器夜间开关：列表卡片不在阅读器语境里，
    // 后者只对正在阅读的那一页有意义。
    val isNight = LegadoTheme.isDark
    val textColor = decoration?.textColor.resolveMarkingColor(isNight)
    val backgroundColor = decoration?.backgroundColor.resolveMarkingColor(isNight)
    val underlineMode = decoration?.underlineMode ?: 0
    // 线色兜底跟随字色，与正文 `underlineColor ?: textColor ?: 正文色` 同序；
    // 最后一级用主题正文色而不是阅读器配置——列表不在阅读器语境里，
    // 且与正文一致：兜底色不参与反相（正文的兜底在 resolveModeColor 之后才取）。
    val lineColor = decoration?.underlineColor.resolveMarkingColor(isNight)
        ?: textColor
        ?: LegadoTheme.colorScheme.onSurface

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

/**
 * 夜间把日间色按 HSL 明度反相（[ColorUtils.flipLightness]），alpha 原样保留。
 *
 * 对应正文 `LegacyReaderStyleRangeMapper.resolveModeColor` 在 `nightColor == null`
 * 时的派生分支：笔记样式不存夜间色，所以夜间只能在日间色上派生。null 保持 null
 * ——正文同样不会拿兜底色去做反相。
 */
private fun Color?.resolveMarkingColor(isNight: Boolean): Color? {
    val dayColor = this ?: return null
    return if (isNight) Color(ColorUtils.flipLightness(dayColor.toArgb())) else dayColor
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
 * 把 `ReaderSvgPathCache` 缓存的 `android.graphics.Path` 重放成 Compose Path。
 *
 * 为什么需要转换：`ReaderSvgPathCache` 给的是 `android.graphics.Path`（正文用
 * android Canvas 画），而 Compose 的 `drawPath` 只收 Compose Path，且 Compose 只提供了
 * compose→android 的 `asAndroidPath()`，**没有** android→compose 的反向扩展。
 *
 * 为什么放在 UI 层而不是给 `ReaderSvgPathCache` 加一个 Compose 版本：正文绘制层不该
 * 为了预览引入 Compose 依赖。代价是 mode 5 每次绘制重放一遍顶点；SVG 路径顶点数有限、
 * 自定义 SVG 又是少数场景，这点开销可以接受。
 *
 * CONIC_TO（带 weight 的二次贝塞尔）Compose 没有对应 API，整条放弃而不是画错。
 */
private fun android.graphics.Path.toComposePath(): Path? = try {
    val iterator = PathCompat.getPathIterator(this)
    val compose = Path()
    while (!iterator.isAtEnd) {
        when (iterator.currentSegmentType) {
            PathIterator.SegmentType.MOVE_TO ->
                compose.moveTo(iterator.currentX, iterator.currentY)

            PathIterator.SegmentType.LINE_TO ->
                compose.lineTo(iterator.currentX, iterator.currentY)

            // current* 是控制点，next* 是终点
            PathIterator.SegmentType.QUAD_TO -> compose.quadraticBezierTo(
                iterator.currentX, iterator.currentY,
                iterator.nextX, iterator.nextY,
            )

            // current* / next* 是两个控制点，nextNext* 是终点
            PathIterator.SegmentType.CUBIC_TO -> compose.cubicTo(
                iterator.currentX, iterator.currentY,
                iterator.nextX, iterator.nextY,
                iterator.nextNextX, iterator.nextNextY,
            )

            PathIterator.SegmentType.CLOSE -> compose.close()

            PathIterator.SegmentType.CONIC_TO -> return null
        }
        iterator.next()
    }
    compose
} catch (e: IllegalArgumentException) {
    // PathCompat 对非法 path 会抛，视为这条 SVG 画不了
    null
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
                    quadraticBezierTo(
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
            val path = ReaderSvgPathCache.parse(svgPath)?.toComposePath() ?: return
            withTransform({
                translate(left = svgLeft, top = y - READER_SVG_BASELINE_Y)
                scale(
                    scaleX = (svgRight - svgLeft) / READER_SVG_BASE_WIDTH,
                    scaleY = 1f,
                    pivot = Offset.Zero,
                )
            }) {
                drawPath(path = path, brush = solidBrush, style = Stroke(width = strokeWidth, cap = cap))
            }
        }
    }
}
