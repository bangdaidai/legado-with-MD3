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

/** 双实线两条线之间的净间隙，不含线宽。 */
const val READER_DOUBLE_LINE_GAP_DP = 3f

/**
 * 双实线第二条线的纵坐标偏移（px）：净间隙 + **线芯**宽度，画在第一条线下方。
 *
 * 正文（`ReaderUnderlineDrawCommand`）、选区预览（`ReaderCanvasSurface`）和笔记列表
 * （`MarkingStyledText.drawUnderlineShape`）三处共用：正文与笔记列表曾各自实现，
 * 正文的绘制分支丢失后双实线塌成单线、选区预览却还是双线 —— 两处看起来不同。
 * 偏移用线芯宽度（未抬下限），与两端描边口径的关系由绘制层自己处理。
 */
fun doubleLineSecondOffsetPx(gapPx: Float, coreWidthPx: Float): Float =
    gapPx.coerceAtLeast(0f) + coreWidthPx

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
 *
 * 端点圆头的半径也由本函数决定：圆头半径是实际描边宽度的一半，若按未抬升的
 * 线芯宽度算，亚像素线宽的圆头会比线芯多探出小半个像素。
 */
fun finalStrokeWidthPx(coreWidthPx: Float): Float = coreWidthPx.coerceAtLeast(1f)

/** 周期笔画（波浪/虚线）的最小段长，防止除零和退化配置。 */
const val READER_MIN_STROKE_SEGMENT_PX = 0.1f

/**
 * 所有下划线两端统一使用的圆角半径（dp）。
 *
 * 原先是「端点圆角」开关，开着给半圆（描边半径 = 半个线宽）、关着给方角。两种
 * 极端都不好看：方角边生硬，半圆又太圆，而且开关让同一份样式有两套形态。
 * 现在统一给一个 1dp 小圆角——在原有笔形上收一点边，不改变整体形状。
 *
 * 必须**固定**而不是跟着线宽走。Android 的 round cap 半径恒等于 `strokeWidth / 2`，
 * 若让羽化加粗到 `widthPx + 2 × feather` 的外圈趟也带圆头，半径就是半个羽化半径
 * 那么远（宽度 1dp / 羽化 5dp 时约 5.5dp），端头看着像在高亮范围外多接一段。
 * 所以圆头只由**线芯那一趟**承担，见 `ReaderUnderline.cornerRadiusPx`。
 */
const val READER_UNDERLINE_CORNER_DP = 1f

/**
 * 圆角半径（px）。半径不超过半个线宽：线芯本来就只有那么粗，圆角再大就只是
 * 把端点变成半圆，那正是旧「端点圆角」开关开着的样子。
 *
 * 收进上限是**必须**的：Android 的 round cap 半径恒等于 `strokeWidth / 2`，
 * 外圈那些加粗到 `widthPx + 2 × feather` 的趟若也带圆头，半径就是半个羽化半径
 * 那么远（宽度 1dp / 羽化 5dp 时约 5.5dp），端头看着像在高亮范围外多接一段。
 *
 * dp→px 由调用方换算：本模块要能被纯 JVM 单测覆盖，不引入 Android 依赖。
 */
fun underlineCornerRadiusPx(strokeWidthPx: Float, cornerRadiusPx: Float): Float =
    if (cornerRadiusPx <= 0f) 0f
    else cornerRadiusPx.coerceAtMost(strokeWidthPx.coerceAtLeast(1f) / 2f)

/**
 * 描边羽化剖面的高斯 sigma（相对羽化半径）。7 级插值用，级数固定所以不再有「趟数」概念。
 */
const val READER_FEATHER_PROFILE_SIGMA = 0.35f

/**
 * 高斯剖面的采样点：归一化距离（0 = 中心，1 = 半径处）与该处的权重。
 *
 * **为什么要多色标拟合而不是多趟叠加。** 「N 趟同心矩形、每趟 alpha 按高斯递减」
 * 看着像高斯，其实是 N 级阶梯：每趟内部 alpha 是常数，合成后从边缘到中心逐级跳变。
 * 密度 3、羽化 5dp 时一趟宽度差约 2px，15 趟就是 15 条可见横线 —— 正是
 * 「一层透明的」加「分界线明显」的来源，边缘区的 alpha 还会被叠加累加到接近 1。
 *
 * 改成**一趟 + 连续剖面**：`LinearGradient` 按这些点分段插值，7 级看不出折线。
 * 权重为 `exp(-t² / (2σ²))`，σ = [READER_FEATHER_PROFILE_SIGMA]，从中心到边缘递减。
 */
val READER_FEATHER_PROFILE_POSITIONS = floatArrayOf(0f, 0.12f, 0.28f, 0.5f, 0.72f, 0.88f, 1f)
val READER_FEATHER_PROFILE_WEIGHTS = floatArrayOf(1f, 0.94f, 0.76f, 0.52f, 0.28f, 0.11f, 0.04f)

/**
 * 端部柔边宽度占**段宽**的上限。
 *
 * 端部柔边名义上取羽化半径（与上下扩散同量，模糊才各向同性），但高亮段常常只有
 * 1~3 个字：宽度 1dp / 羽化 5dp 时上下已经糊成 11dp 粗，段宽才 15~45dp，两端的
 * 柔边互相重叠 —— 整条线只剩中段最实、两端淡掉，是个纺锤形。
 *
 * 封顶到这个比例后，短段两端仍有柔边，中段又保得住实心。
 */
const val READER_EDGE_FADE_SEG_RATIO = 0.25f

/**
 * 羽化后的单侧扩散量（px）：线芯半宽 + 羽化半径。
 *
 * 垂直方向的模糊范围，也是 `LinearGradient` 垂直渐变的半轴长度。
 */
fun featherSpreadPx(strokeWidthPx: Float, featherPx: Float): Float =
    finalStrokeWidthPx(strokeWidthPx) / 2f + featherPx.coerceAtLeast(0f)

/**
 * 水平方向端部渐隐占**段宽**的比例。
 *
 * 端部柔和宽度与垂直方向同量（羽化半径），各向同性；但 1~3 个字的短段撑不住，
 * 两端会重叠成纺锤形，所以按段宽封顶。
 */
fun edgeFadeRatio(featherPx: Float, segWidthPx: Float): Float {
    if (featherPx <= 0f || segWidthPx <= 0f) return 0f
    return (featherPx / segWidthPx).coerceAtMost(READER_EDGE_FADE_SEG_RATIO)
}

/**
 * 荧光色带（填充块）的边缘柔化色标：`上升沿 + 平台 + 下降沿`。
 *
 * 与描边的**中心对称**剖面形状不同 —— 这是两种东西：
 * - 描边是「一条线」，模糊后中心最浓、上下左右对称衰减
 * - 色带是「一块填充」，羽化只该软化边缘、中间仍是实心色块
 *
 * 上升沿宽度 = 羽化半径，按 [extentPx] 归一化后封顶到 0.5：再宽中间平台就没了，
 * 色带退化成「中间一点、两边全虚」，模糊调大反而看不出差别（旧的多趟内缩实现正是
 * 这么塌的，所以「模糊 1」和「模糊 10」看起来一样）。
 *
 * 返回 (positions, alphas)，都相对该维度的长度，交给 `LinearGradient` 一次插值。
 */
fun featherEdgeStops(featherPx: Float, extentPx: Float): Pair<FloatArray, FloatArray> {
    val n = READER_FEATHER_PROFILE_POSITIONS.size
    val rise = if (extentPx > 0f && featherPx > 0f) {
        (featherPx / extentPx).coerceIn(0f, 0.5f)
    } else {
        0f
    }
    val positions = FloatArray(n * 2)
    val alphas = FloatArray(n * 2)
    for (i in 0 until n) {
        // 上升沿：位置 0 → rise，alpha 由最弱升到最强（权重表是中心最强，倒着取）
        positions[i] = READER_FEATHER_PROFILE_POSITIONS[i] * rise
        alphas[i] = READER_FEATHER_PROFILE_WEIGHTS[n - 1 - i]
        // 下降沿：位置 1-rise → 1，alpha 由最强降到最弱；中间自然连成平台
        positions[n + i] = (1f - rise) + READER_FEATHER_PROFILE_POSITIONS[i] * rise
        alphas[n + i] = READER_FEATHER_PROFILE_WEIGHTS[i]
    }
    return positions to alphas
}

/**
 * 描边羽化的**垂直**剖面色标：路径处最浓、向上下两缘对称衰减。
 *
 * 与 [featherEdgeStops] 的「边缘型」剖面（软化填充块的边、中间保持实心）不同，
 * 描边是「一条线」：垂直渐变铺满整条描边带，路径处 alpha 最高，向上向下都按
 * [READER_FEATHER_PROFILE_WEIGHTS] 的高斯衰减。
 *
 * [plateauHalfRatio] 是满浓平台的**半宽占比**（相对渐变半跨距），供波浪使用：
 * 波浪路径在基线上下 ± 峰高之间起伏，若浓度中心锁死在基线（平台为 0），波峰波谷
 * 处的路径点会落到渐变的低权重区，波形对比被人为抹平——「模糊把波浪整没了」
 * 正是它。平台盖住 [±峰高] 后路径各点都保持线色浓度，羽化只柔化边缘，波形不变。
 * 默认 0（无平台）时退化为中心峰剖面，实线/虚线照旧。
 *
 * 回归背景：旧实现把 [READER_FEATHER_PROFILE_POSITIONS] 那份「中心→半径」的单调
 * 剖面直接铺在描边带自上而下的方向上，结果是上缘 alpha 满浓生硬切断、线芯只有
 * 半浓、下缘几乎透明 —— 正文里下划线/波浪「只有下方模糊、上缘一条实边、波浪
 * 下半部糊成平带」正是它。这里把半剖面沿中心折叠成全剖面：两端最弱、中段最强、
 * 上下对称。
 *
 * 返回 (positions, alphas)，positions 相对渐变半跨距（0=上缘、1=下缘）。
 */
fun featherVerticalStops(plateauHalfRatio: Float = 0f): Pair<FloatArray, FloatArray> {
    val n = READER_FEATHER_PROFILE_POSITIONS.size
    val p = plateauHalfRatio.coerceIn(0f, 0.5f)
    val rise = 0.5f - p
    val positions = FloatArray(n * 2 - 1 + if (p > 0f) 1 else 0)
    val alphas = FloatArray(positions.size)
    for (i in 0 until n) {
        // 上升沿 0→0.5-p：权重从上缘最弱升到路径平台最强
        positions[i] = READER_FEATHER_PROFILE_POSITIONS[i] * rise
        alphas[i] = READER_FEATHER_PROFILE_WEIGHTS[n - 1 - i]
    }
    var cursor = n
    if (p > 0f) {
        // 平台右端：0.5-p（上升沿末点）到 0.5+p 之间由这两个同浓色标连成常量 1.0
        positions[cursor] = 0.5f + p
        alphas[cursor] = 1f
        cursor++
    }
    for (i in 1 until n) {
        // 下降沿 0.5+p→1：权重从路径平台最强降到下缘最弱（跳过 i=0，平台右端已有色标）
        positions[cursor] = (0.5f + p) + READER_FEATHER_PROFILE_POSITIONS[i] * rise
        alphas[cursor] = READER_FEATHER_PROFILE_WEIGHTS[i]
        cursor++
    }
    return positions to alphas
}

/** 把 [featherEdgeStops] 的 alpha 乘上基色 alpha，量化成 8bit 色标。 */
fun featherEdgeColors(alphas: FloatArray, colorArgb: Int): IntArray {
    val rgb = colorArgb and 0x00FFFFFF
    val alpha = (colorArgb ushr 24) and 0xFF
    return IntArray(alphas.size) { i ->
        val a = (alpha * alphas[i]).toInt().coerceIn(0, 255)
        rgb or (a shl 24)
    }
}

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
    /**
     * 边缘柔化（UI 文案「羽化」）。
     *
     * 描边：**各向同性**——每趟是四周同时缩向核心的形状（上下靠加粗、两端靠内缩），
     * alpha 按高斯递增，于是上下左右一样糊。自定义 SVG 不支持：它的形状是用户
     * 给的路径，柔边无从定义。
     * 填充色带：同矩形多层 alpha 叠加 + 向内收缩，与描边同一口径。
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
    feather = false,
    dashPattern = false,
)

/**
 * 荧光色带：不是描边，但色带本身有边缘，所以柔化成立——同矩形多层 alpha 叠加，
 * 边界化开到透明。
 */
private val BAND_SUPPORT = UnderlineControlSupport(
    width = false,
    offset = false,
    layer = false,
    feather = true,
    dashPattern = false,
)

/**
 * 双实线：两条线各自独立成段，柔化作用在「整段」上而不是单条线，视觉上和单实线
 * 没有区别、参数却互相干扰（会把两条线之间的间隙糊掉），所以羽化对双线不开放。
 */
private val DOUBLE_LINE_SUPPORT = UnderlineControlSupport(
    width = true,
    offset = true,
    layer = true,
    feather = false,
    dashPattern = false,
)

/** 线型编号与 `HighlightRule.underlineMode` / `ReaderUnderline.mode` 一致。 */
fun underlineControlSupport(mode: Int): UnderlineControlSupport = when (mode) {
    0 -> NO_GEOMETRY // 无
    1 -> strokeSupport() // 实线
    2 -> strokeSupport(dashPattern = true) // 虚线
    3 -> strokeSupport(waveShape = true) // 波浪
    4 -> DOUBLE_LINE_SUPPORT // 双实线：羽化会把两线之间的间隙糊掉，不开放
    // 自定义 SVG：形状是用户给的路径，柔边与收边都无从定义
    5 -> strokeSupport(feather = false)
    // 删除线：固定落在行高比例处，不吃偏移；羽化同样会把删除线糊到字上
    6 -> strokeSupport(offset = false, feather = false)
    7 -> BAND_SUPPORT // 荧光：填充色带，颜色 + 边缘柔化
    else -> NO_GEOMETRY
}

private fun strokeSupport(
    offset: Boolean = true,
    dashPattern: Boolean = false,
    feather: Boolean = true,
    waveShape: Boolean = false,
) = UnderlineControlSupport(
    width = true,
    offset = offset,
    layer = true,
    feather = feather,
    dashPattern = dashPattern,
    waveShape = waveShape,
)
