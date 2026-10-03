package io.legado.app.feature.reader.core.style

import kotlin.math.roundToInt

/**
 * 下划线几何与「哪些参数对当前线型生效」的唯一来源。
 *
 * 背景：正文绘制（`ReaderPageDecorationDrawCache`）、正文内的选中样式预览
 * （`ReaderCanvasSurface.drawSelectionStylePreview`）和高亮规则编辑弹层的预览
 * （`HighlightRulePreview`）历史上各写各的常量与算法，于是「预览调好了、正文不一样」。
 * 这里只放纯数学与参数适用性，不依赖 Android / Compose，因此可被 JVM 单测直接覆盖。
 *
 * 三处绘制都必须调用这里的常量与算法；不要在任何绘制代码里再写第二份
 * `3.dp` / `12.dp` / `0.4f`。
 */

/** 波浪的半波长：一次 `quadTo` 覆盖的宽度。整波长是它的两倍。 */
const val READER_WAVE_HALF_WAVE_DP = 12f

/**
 * 波浪默认控制点的竖直偏移，对应实际峰高 1.5dp。
 *
 * 二次贝塞尔的中点只到控制点的一半，所以**实际峰高是这个值的一半**（3dp → 1.5dp）。
 * 用户可调的峰高存在 `HighlightRule.underlineWavePeak`，默认值 1.5dp 与此一致；
 * 渲染时控制点 = 峰高 × 2。
 */
const val READER_WAVE_CONTROL_OFFSET_DP = 3f

/** 双下划线两条线之间的净间隙，不含线宽。 */
const val READER_DOUBLE_LINE_GAP_DP = 3f

/** 删除线固定落在行高的这个比例处，与偏移参数无关。 */
const val READER_STRIKE_HEIGHT_RATIO = 0.52f

/**
 * 荧光色带的顶边落在行高的这个比例处：它铺的是下半行，不是描边。
 *
 * 线宽/偏移对它没有意义（见 [underlineControlSupport]），但色带有边缘，
 * 所以圆头和羽化都成立：圆头半径取色带高度的一半，羽化是向内收缩的多趟叠加。
 */
const val READER_HALF_HIGHLIGHT_TOP_RATIO = 0.5f

/** 自定义 SVG 下划线的参考坐标系：viewBox 宽与基线在其中的 y。 */
const val READER_SVG_BASE_WIDTH = 100f
const val READER_SVG_BASELINE_Y = 50f

/**
 * 下划线实际描边时的最小线宽（**px**，不是 dp）。
 *
 * 亚像素线宽在低密度屏上会细到几乎不可见（0.5dp × density 1.0 = 0.5px），
 * 所以最终描边那一趟统一抬到 1px。
 *
 * 只在「真正画线」时抬升，绝不能抬羽化的基准宽度：羽化是「基准宽度 + 扩散量」
 * 多趟叠加，基准一旦被抬高，向两侧扩散的量就与之错位，柔边会比原始实现更糊
 * （见 `MarkingStyledText.drawUnderlineSegment` 里刻意不抬基准的注释）。
 *
 * 正文（`ReaderPageDecorationDrawCache`）与笔记列表（`MarkingStyledText`）必须共用
 * 本函数，否则同一条笔记在两处的粗细不一致——这是「同一个宽度两处看起来不同」的
 * 唯一来源。
 */
fun finalStrokeWidthPx(coreWidthPx: Float): Float = coreWidthPx.coerceAtLeast(1f)

/** 周期笔画（波浪/虚线）的最小段长，防止除零和退化配置。 */
const val READER_MIN_STROKE_SEGMENT_PX = 0.1f

/** 每 1dp 羽化半径对应的叠加趟数。 */
const val READER_FEATHER_PASSES_PER_DP = 3f

/** 羽化趟数下限：半径很小时也至少这么多趟，否则看不出柔边。 */
const val READER_FEATHER_MIN_PASSES = 6

/** 羽化趟数上限：半径很大时封顶，避免每帧几十趟描边。 */
const val READER_FEATHER_MAX_PASSES = 24

/** 羽化权重的高斯 sigma。 */
const val READER_FEATHER_SIGMA = 0.55f

/** 羽化叠加趟数。 */
fun featherPassCount(featherDp: Float): Int =
    (featherDp * READER_FEATHER_PASSES_PER_DP).toInt()
        .coerceIn(READER_FEATHER_MIN_PASSES, READER_FEATHER_MAX_PASSES)

/** 羽化的单趟高斯权重：[d] 为该趟离中心的归一化距离，0 最实、1 最虚。 */
fun featherGaussian(d: Float, sigma: Float = READER_FEATHER_SIGMA): Float =
    kotlin.math.exp(-(d * d) / (2f * sigma * sigma)).toFloat()

/**
 * 荧光色带的边缘柔化趟次：向内收缩 + alpha 递减的多趟叠加，硬边化成渐变。
 *
 * [insetFactor] 是相对柔化半径的内缩比例（1 最外最虚 → 0 最实），
 * [alphaScale] 是该趟的 alpha 比例。
 *
 * 描边羽化是「向外加粗 + 端点渐隐」，色带柔化是「向内收缩」，两者共用同一份
 * 高斯权重序列，所以同半径下手感一致。
 */
fun featherBandPasses(featherDp: Float): List<FeatherPass> {
    if (featherDp <= 0f) return listOf(FeatherPass(0f, 1f))
    val passes = featherPassCount(featherDp)
    // 从最实到最虚：内缩比例与 alpha 同步递增
    return List(passes + 1) { index ->
        val d = index.toFloat() / passes
        FeatherPass(insetFactor = d, alphaScale = featherGaussian(d))
    }
}

/** 柔化一趟：[insetFactor] 相对半径的内缩比例，[alphaScale] 该趟的 alpha 比例。 */
data class FeatherPass(
    val insetFactor: Float,
    val alphaScale: Float,
)

/** 把 [widthPx] 均摊成整数个半波后的半波数。 */
fun waveHalfWaveCount(widthPx: Float, halfWavePx: Float): Int =
    (widthPx / halfWavePx.coerceAtLeast(READER_MIN_STROKE_SEGMENT_PX))
        .roundToInt().coerceAtLeast(1)

/**
 * 虚线周期数就近取整后把余量按同比分给段与间隙（段尾不再被硬切成碎段）。
 * 返回 Triple(周期数, 缩放后的段长, 缩放后的间隙)。
 */
fun scaledDashSegments(widthPx: Float, dashOnPx: Float, dashOffPx: Float): Triple<Int, Float, Float> {
    val dashOn = dashOnPx.coerceAtLeast(READER_MIN_STROKE_SEGMENT_PX)
    val dashOff = dashOffPx.coerceAtLeast(READER_MIN_STROKE_SEGMENT_PX)
    val periods = ((widthPx + dashOff) / (dashOn + dashOff)).roundToInt().coerceAtLeast(1)
    val scale = widthPx / (periods * dashOn + (periods - 1) * dashOff)
    return Triple(periods, dashOn * scale, dashOff * scale)
}

/**
 * 一个半波：两端落在基线上，中点位于 `基线 + [controlOffsetY] / 2`。
 * [controlOffsetY] 是控制点偏移，不是峰高。
 */
data class WaveHalfWave(
    val startX: Float,
    val endX: Float,
    val controlOffsetY: Float,
)

/**
 * 把 `startX..endX` 均摊成整数个半波。
 *
 * 余数摊匀后同行内波长一致，行间疏密不随余量跳动；最后一个半波强制收口到
 * [endX]，避免取整误差留下小缝隙。Android `Path` 与 Compose `Path` 共用这份节点。
 */
fun waveHalfWaves(
    startX: Float,
    endX: Float,
    halfWavePx: Float,
    controlOffsetPx: Float,
): List<WaveHalfWave> {
    val width = endX - startX
    if (width <= 0f) return emptyList()
    val halfWaves = waveHalfWaveCount(width, halfWavePx)
    val step = width / halfWaves
    return List(halfWaves) { index ->
        val from = startX + step * index
        val to = if (index == halfWaves - 1) endX else from + step
        WaveHalfWave(
            startX = from,
            endX = to,
            controlOffsetY = if (index % 2 == 0) -controlOffsetPx else controlOffsetPx,
        )
    }
}

/**
 * 某个 `underlineMode` 下哪些参数真的有效果。
 *
 * 用途：编辑弹层据此决定显示哪些控件。判定必须与渲染层一致——
 * 正文对 [io.legado.app.feature.reader.core.model.ReaderPageDecorationDrawCache]
 * 的分派是：mode 7 走填充色带（`halfHighlights`），其余走描边命令
 * （`ReaderUnderlineDrawCommand`）；描边命令里 mode 6 固定落在行高
 * [READER_STRIKE_HEIGHT_RATIO]，不看偏移参数。
 */
data class UnderlineControlSupport(
    /** 线宽。荧光是填充色带，没有线宽概念。 */
    val width: Boolean,
    /** 竖直偏移。删除线固定在行高比例处，荧光铺下半行，两者都不吃偏移。 */
    val offset: Boolean,
    /** 画在文字上方还是下方。荧光恒在文字下方（否则糊字）。 */
    val layer: Boolean,
    /** 端点圆角。荧光色带可整体圆头化（半径取色带高度的一半）。 */
    val roundCap: Boolean,
    /**
     * 边缘柔化（UI 文案「羽化」）。
     *
     * 描边：向外加粗 + 端点渐隐。填充色带：同矩形多层 alpha 叠加模拟模糊。
     * 自定义 SVG 不支持——它画的是用户给的路径，"两端"无从定义。
     */
    val feather: Boolean,
    /** 自定义虚线段长 / 间隔。只有虚线用。 */
    val dashPattern: Boolean,
    /** 波浪峰高 / 波长。只有波浪用。 */
    val waveShape: Boolean = false,
)

private val NO_GEOMETRY = UnderlineControlSupport(
    width = false,
    offset = false,
    layer = false,
    roundCap = false,
    feather = false,
    dashPattern = false,
)

/**
 * 荧光色带：不是描边，但色带本身有边缘，所以圆头和柔化都成立——
 * 圆头 = 整条色带两端半圆化；柔化 = 同矩形多层 alpha 叠加，边界化开到透明。
 */
private val BAND_SUPPORT = UnderlineControlSupport(
    width = false,
    offset = false,
    layer = false,
    roundCap = true,
    feather = true,
    dashPattern = false,
)

/**
 * 双下划线：两条线各自独立成段，端点圆头和羽化都作用在「整段」上而不是单条线，
 * 视觉上和单实线没有区别、参数却互相干扰（圆头会把两条线的间距吃掉），
 * 所以这两个参数对双线不开放。
 */
private val DOUBLE_LINE_SUPPORT = UnderlineControlSupport(
    width = true,
    offset = true,
    layer = true,
    roundCap = false,
    feather = false,
    dashPattern = false,
)

/** 线型编号与 `HighlightRule.underlineMode` / `ReaderUnderline.mode` 一致。 */
fun underlineControlSupport(mode: Int): UnderlineControlSupport = when (mode) {
    0 -> NO_GEOMETRY // 无
    1 -> strokeSupport() // 实线
    2 -> strokeSupport(dashPattern = true) // 虚线
    3 -> strokeSupport(waveShape = true) // 波浪
    4 -> DOUBLE_LINE_SUPPORT // 双下划线：不开放圆头与羽化
    5 -> strokeSupport(roundCap = false, feather = false) // 自定义 SVG：不开放圆头与羽化
    6 -> strokeSupport(offset = false, roundCap = false, feather = false) // 删除线
    7 -> BAND_SUPPORT // 荧光：填充色带，颜色 + 圆头 + 边缘柔化
    else -> NO_GEOMETRY
}

private fun strokeSupport(
    offset: Boolean = true,
    dashPattern: Boolean = false,
    roundCap: Boolean = true,
    feather: Boolean = true,
    waveShape: Boolean = false,
) = UnderlineControlSupport(
    width = true,
    offset = offset,
    layer = true,
    roundCap = roundCap,
    feather = feather,
    dashPattern = dashPattern,
    waveShape = waveShape,
)