package io.legado.app.ui.widget.components.text

import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import android.graphics.Shader
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
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.domain.model.TextProcessStyle
import io.legado.app.feature.reader.core.style.READER_DOUBLE_LINE_GAP_DP
import io.legado.app.feature.reader.core.style.READER_HALF_HIGHLIGHT_TOP_RATIO
import io.legado.app.feature.reader.core.style.underlineCornerRadiusPx
import io.legado.app.feature.reader.core.style.READER_UNDERLINE_CORNER_DP
import io.legado.app.feature.reader.core.style.READER_STRIKE_HEIGHT_RATIO
import io.legado.app.feature.reader.core.style.READER_SVG_BASE_WIDTH
import io.legado.app.feature.reader.core.style.READER_SVG_BASELINE_Y
import io.legado.app.feature.reader.core.style.READER_WAVE_CONTROL_OFFSET_DP
import io.legado.app.feature.reader.core.style.READER_WAVE_HALF_WAVE_DP
import io.legado.app.feature.reader.core.style.finalStrokeWidthPx
import io.legado.app.feature.reader.core.style.featherEdgeColors
import io.legado.app.feature.reader.core.style.featherEdgeStops
import io.legado.app.feature.reader.core.style.scaledDashSegments
import io.legado.app.feature.reader.core.style.waveHalfWaves
import io.legado.app.feature.reader.platform.ReaderSvgPathCache
import io.legado.app.feature.reader.platform.ReaderUnderlineDrawCommand
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
    textAlign: TextAlign? = null,
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
            textAlign = textAlign,
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
        textAlign = textAlign,
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
    feather: Float,
) {
    result.forEachLineSegment(0, textLength) { left, right, top, bottom, _ ->
        drawFluorescentBand(
            left = left,
            right = right,
            top = top,
            bottom = bottom,
            color = color,
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
 * 柔化趟次与 alpha 都来自 `ReaderUnderlineGeometry`）。边缘柔化是「同一矩形从最虚
 * 画到最实」，靠多层 alpha 叠加把硬边化成渐变。
 */
internal fun DrawScope.drawFluorescentBand(
    left: Float,
    right: Float,
    top: Float,
    bottom: Float,
    color: Color,
    feather: Float,
) {
    val bandTop = top + (bottom - top) * READER_HALF_HIGHLIGHT_TOP_RATIO
    val bandHeight = (bottom - bandTop).coerceAtLeast(0f)
    if (bandHeight <= 0f || right <= left) return
    // 两端统一收小圆角，与正文 ReaderHalfHighlightDrawCommand 同一口径；没有开关
    val radius = READER_UNDERLINE_CORNER_DP.dp.toPx().coerceAtMost(bandHeight / 2f)
    val featherPx = feather.dp.toPx()

    // 边缘柔化与正文同一套剖面（featherEdgeStops）：上下两边缘与两端化开，中间保持
    // 实心色块。不用多趟叠加 —— 叠加是 N 级阶梯，且大模糊半径会把色带中间吃空，
    // 于是「模糊 1」和「模糊 10」看起来一样。
    val (vPos, vAlpha) = featherEdgeStops(featherPx, bandHeight)
    val (hPos, hAlpha) = featherEdgeStops(featherPx, right - left)
    val argb = color.toArgb()

    // DST_IN 让两个方向的 alpha 相乘、颜色取 dst（两者都用同一线色）
    drawIntoCanvas { canvas ->
        val vertical = LinearGradient(
            0f, bandTop, 0f, bottom,
            featherEdgeColors(vAlpha, argb),
            vPos,
            Shader.TileMode.CLAMP,
        )
        val horizontal = LinearGradient(
            left, 0f, right, 0f,
            featherEdgeColors(hAlpha, argb),
            hPos,
            Shader.TileMode.CLAMP,
        )
        // 全限定名：本文件同时用 Compose 的 `Paint`（drawPath 那套）与
        // android.graphics 的 `Paint`（要挂 ComposeShader，Compose 的 Brush 没有
        // 二维相乘的等价物），同名不限定会被解析成 Compose 那个。
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = ComposeShader(horizontal, vertical, PorterDuff.Mode.DST_IN)
        }
        canvas.drawRoundRect(left, bandTop, right, bottom, radius, radius, paint)
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

/**
 * 纯色笔刷，用原生 [android.graphics.LinearGradient] 生成。
 *
 * 羽化路径已经整体委托给 `ReaderUnderlineDrawCommand`（见 [drawWithReaderUnderlineCommand]），
 * 那里自带端部渐隐 shader；这里只服务**非羽化**的兜底绘制。用原生 shader 而非
 * Compose 的 `Brush.linearGradient`：后者在 unpremultiplied 空间插值，半透明色
 * 与正文（premultiplied）会有偏差。
 */
private fun solidShaderBrush(colorArgb: Int): Brush {
    // 不改 alpha：色标原样进 shader，半透明色仍是半透明
    val shader = LinearGradient(
        0f, 0f, 1f, 0f,
        intArrayOf(colorArgb, colorArgb),
        floatArrayOf(0f, 1f),
        Shader.TileMode.CLAMP,
    )
    return ShaderBrush(shader)
}

/**
 * 用**正文那条绘制命令**画一段下划线。
 *
 * 预览此前自己实现了一套羽化叠加，与正文在 alpha 量化、渐变插值空间、每趟线宽基准、
 * 端部渐隐长度上都不同，任何一项分叉都会在多趟叠加后放大成肉眼可辨的深浅差。
 * 现在改为构造同一条 [ReaderUnderlineDrawCommand] 并直接画，两边共用同一份代码，
 * 预览即所见即正文。
 */
private fun DrawScope.drawWithReaderUnderlineCommand(
    mode: Int,
    colorArgb: Int,
    widthPx: Float,
    startX: Float,
    endX: Float,
    y: Float,
    featherPx: Float,
    dashOnPx: Float,
    dashOffPx: Float,
    svgPath: String,
    waveControlOffsetPx: Float,
    waveHalfWavePx: Float,
) {
    val command = ReaderUnderlineDrawCommand.forSegment(
        mode = mode,
        colorArgb = colorArgb,
        widthPx = widthPx,
        startX = startX,
        endX = endX,
        y = y,
        featherPx = featherPx,
        dashOnPx = dashOnPx,
        dashOffPx = dashOffPx,
        svgPath = svgPath,
        waveControlOffsetPx = waveControlOffsetPx,
        waveHalfWavePx = waveHalfWavePx,
    )
    drawIntoCanvas { canvas -> command.draw(canvas.nativeCanvas) }
}

internal fun DrawScope.drawUnderlineSegment(
    mode: Int,
    color: Color,
    widthDp: Float,
    startX: Float,
    endX: Float,
    y: Float,
    feather: Float = 0f,
    dashLen: Float = 8f,
    dashGap: Float = 5f,
    svgPath: String = "",
    wavePeakDp: Float = READER_WAVE_CONTROL_OFFSET_DP / 2f,
    waveLengthDp: Float = READER_WAVE_HALF_WAVE_DP * 2f,
) {
    // 所有线型统一收 1dp 小圆角（READER_UNDERLINE_CORNER_DP），没有开关。
    // 羽化的外圈趟用平齐切口：round cap 半径恒等于 strokeWidth/2，外圈加粗到
    // widthPx + 2×featherPx 时圆角会涨到半个羽化半径那么远，端头像多接一段。
    val cap = StrokeCap.Round
    // 线宽在这里换算。刻意不做下限抬升：羽化 pass 的基准宽度一旦被抬高，向两侧
    // 扩散的量随之错位，柔边会比原始实现更糊。亚像素宽度的抬升只发生在最终描边
    // 那一趟（drawUnderlineShape 内的 finalStrokeWidthPx），不参与扩散量的计算；
    // 正文走同一个函数，两侧粗细一致。
    val coreWidth = widthDp.dp.toPx()
    // 收边量与正文 ReaderUnderline.capInsetPx 同一公式（半径不超过半个线宽）。
    val capInset = underlineCornerRadiusPx(coreWidth, READER_UNDERLINE_CORNER_DP.dp.toPx())
    if (feather > 0f) {
        // 羽化**直接复用正文的绘制命令**：预览自己那套多趟叠加曾多次与正文分叉
        // （插值空间、8bit 量化、趟宽、端部渐隐长度），改成同一处绘制后物理上
        // 不可能再不一致。
        drawWithReaderUnderlineCommand(
            mode = mode,
            colorArgb = color.toArgb(),
            widthPx = coreWidth,
            startX = startX,
            endX = endX,
            y = y,
            featherPx = feather.dp.toPx(),
            dashOnPx = dashLen.dp.toPx(),
            dashOffPx = dashGap.dp.toPx(),
            svgPath = svgPath,
            waveControlOffsetPx = (wavePeakDp * 2f).dp.toPx(),
            waveHalfWavePx = (waveLengthDp / 2f).dp.toPx(),
        )
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
 * 可见性保持 private：选区实时预览（`ReaderCanvasSurface.drawSelectionStylePreview`）
 * 不自己转路径，而是经 [drawUnderlineSegment] 转发到 `drawUnderlineShape`，
 * 两处共用同一份转换与变换，不会各自实现后与正文漂移。
 *
 * 怎么重放：用平台自带的 `Path.approximate()`（API 26 引入，本项目 minSdk 26）把整条路径
 * 近似成 MOVE_TO/LINE_TO 折线再逐点重放，**不需要版本分支、也不需要新依赖**。
 *
 * 两个刻意不用的 API：`android.graphics.PathIterator` 是 API 34 才有的类型，收进来就得加
 * `Build.VERSION` 分支；`androidx.core.graphics` 下也**没有** `PathCompat` / `PathIterator`
 * 这两个类（那个包里只有 `Path.flatten`、`PathUtils`、`PathSegment`），早期版本曾误用，
 * 表现为 `Unresolved reference` 编译失败。
 *
 * 代价是曲线被近似成折线：0.5px 弦高误差对几 dp 粗的下划线不可见。正文仍走原生
 * `canvas.drawPath` 保持精确曲线，预览只求形状与位置对齐。
 */
private fun android.graphics.Path.toComposePath(): Path? {
    val flat = approximate(READER_SVG_FLATTEN_TOLERANCE_PX)
    val pointCount = flat.size / 3
    // 只有一个起点画不出任何笔画，直接当作画不了
    if (pointCount < 2) return null
    val compose = Path()
    for (index in 0 until pointCount) {
        val base = index * 3
        val x = flat[base + 1]
        val y = flat[base + 2]
        // approximate 只产出 MOVE_TO / LINE_TO；出现别的动词说明平台行为变了，
        // 与原来的 CONIC_TO 一样整条放弃，而不是画错
        when (flat[base].toInt()) {
            PATH_VERB_MOVE -> compose.moveTo(x, y)
            PATH_VERB_LINE -> compose.lineTo(x, y)
            else -> return null
        }
    }
    return compose
}

/** `Path.approximate` 的动词：0 = MOVE_TO，1 = LINE_TO */
private const val PATH_VERB_MOVE = 0
private const val PATH_VERB_LINE = 1

/** 折线近似容差（px），与 `androidx.core.graphics.Path.flatten` 的默认值一致 */
private const val READER_SVG_FLATTEN_TOLERANCE_PX = 0.5f

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
    // 兜底笔刷也用原生 shader：Compose 的 Brush.linearGradient 在 unpremultiplied
    // 空间插值，与正文（premultiplied）不同，半透明色会有偏差
    val solidBrush = brush ?: solidShaderBrush(color.toArgb())
    // 线宽只在这一处（所有线型的单一出口）抬到最小可见宽度：亚像素线宽在低密度屏上
    // 几乎不可见。正文走同一个 finalStrokeWidthPx，保证同一条笔记在正文与列表里
    // 粗细一致。羽化的基准宽度在 drawUnderlineSegment 里刻意没抬，两者不是同一趟。
    val stroke = finalStrokeWidthPx(strokeWidth)
    when (mode) {
        1 -> drawLine(
            brush = solidBrush,
            start = Offset(sx, y),
            end = Offset(ex, y),
            strokeWidth = stroke,
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
                    strokeWidth = stroke,
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
                style = Stroke(width = stroke, cap = cap),
            )
        }

        4 -> {
            // 双线全部画在基线下方：第二条 = y + 净间隙 + 线宽（与正文同），
            // 不是围绕 y 上下对称。间隙用线芯宽度而非抬升后的 stroke，与正文
            // ReaderPageDecorationDrawCommand 的 secondY 保持同一口径
            val secondY = y + READER_DOUBLE_LINE_GAP_DP.dp.toPx() + strokeWidth
            drawLine(
                brush = solidBrush,
                start = Offset(sx, y),
                end = Offset(ex, y),
                strokeWidth = stroke,
                cap = cap,
            )
            drawLine(
                brush = solidBrush,
                start = Offset(sx, secondY),
                end = Offset(ex, secondY),
                strokeWidth = stroke,
                cap = cap,
            )
        }

        5 -> {
            // 自定义 SVG：与正文同一套变换（按 viewBox 宽横向拉伸、基线对齐到 y）。
            // 正文用的是整段 bounds 而不是圆头内缩后的区间，这里把内缩加回去。
            // SVG 不开放端点圆角（见 underlineControlSupport），capInset 实际恒为 0。
            val svgLeft = sx - capInset
            val svgRight = ex
            val path = ReaderSvgPathCache.parse(svgPath)?.toComposePath() ?: return
            withTransform({
                translate(left = svgLeft, top = y - READER_SVG_BASELINE_Y)
                scale(
                    scaleX = (svgRight - svgLeft) / READER_SVG_BASE_WIDTH,
                    scaleY = 1f,
                    pivot = Offset.Zero,
                )
            }) {
                drawPath(path = path, brush = solidBrush, style = Stroke(width = stroke, cap = cap))
            }
        }
    }
}
