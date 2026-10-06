package io.legado.app.feature.reader.platform

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Shader
import android.util.LruCache
import androidx.core.graphics.PathParser
import io.legado.app.feature.reader.core.model.ReaderElement
import io.legado.app.feature.reader.core.model.ReaderPage
import io.legado.app.feature.reader.core.model.ReaderRect
import io.legado.app.feature.reader.core.model.ReaderUnderline
import io.legado.app.feature.reader.core.model.underlineRuns
import io.legado.app.feature.reader.core.style.READER_DOUBLE_LINE_GAP_DP
import io.legado.app.feature.reader.core.style.READER_HALF_HIGHLIGHT_TOP_RATIO
import io.legado.app.feature.reader.core.style.READER_STRIKE_HEIGHT_RATIO
import io.legado.app.feature.reader.core.style.READER_SVG_BASE_WIDTH
import io.legado.app.feature.reader.core.style.READER_SVG_BASELINE_Y
import io.legado.app.feature.reader.core.style.READER_UNDERLINE_CORNER_DP
import io.legado.app.feature.reader.core.style.doubleLineSecondOffsetPx
import io.legado.app.feature.reader.core.style.edgeFadeRatio
import io.legado.app.feature.reader.core.style.featherEdgeColors
import io.legado.app.feature.reader.core.style.featherEdgeStops
import io.legado.app.feature.reader.core.style.featherSpreadPx
import io.legado.app.feature.reader.core.style.featherVerticalStops
import io.legado.app.feature.reader.core.style.finalStrokeWidthPx
import io.legado.app.feature.reader.core.style.scaledDashSegments
import io.legado.app.feature.reader.core.style.waveHalfWaves
import io.legado.app.utils.dpToPx

/** 线型编号与 `HighlightRule.underlineMode` / `ReaderUnderline.mode` 一致。 */
private const val DASH_MODE = 2
private const val WAVE_MODE = 3
private const val DOUBLE_LINE_MODE = 4

/** 自定义 SVG：形状完全由用户给的路径决定，收边与柔化都不参与。 */
private const val SVG_MODE = 5

/** Immutable Android draw data prepared once for a page snapshot revision. */
internal data class ReaderPageDecorationDrawCache(
    val contentRules: List<ReaderRuleDrawCommand>,
    val halfHighlights: List<ReaderHalfHighlightDrawCommand>,
    /** 文字层之下的一批自定义下划线（旧 `underlineBelowText`），必须与荧光带同在画字前绘制。 */
    val belowStyledUnderlines: List<ReaderUnderlineDrawCommand>,
    val styledUnderlines: List<ReaderUnderlineDrawCommand>,
    val overlayRules: List<ReaderRuleDrawCommand>,
) {

    companion object {
        fun create(page: ReaderPage) = ReaderPageDecorationDrawCache(
            contentRules = page.elements.filterIsInstance<ReaderElement.Rule>()
                .filterNot(ReaderElement.Rule::overlayStyledUnderline)
                .map(::ReaderRuleDrawCommand),
            halfHighlights = page.underlineRuns().filter { it.underline.mode == 7 }.map { run ->
                ReaderHalfHighlightDrawCommand(run.bounds, run.underline)
            },
            belowStyledUnderlines = page.underlineRuns()
                .filter { it.underline.mode != 7 && it.underline.belowText }
                .map { run ->
                    ReaderUnderlineDrawCommand(run.bounds, run.underline)
                },
            styledUnderlines = page.underlineRuns()
                .filter { it.underline.mode != 7 && !it.underline.belowText }
                .map { run ->
                    ReaderUnderlineDrawCommand(run.bounds, run.underline)
                },
            overlayRules = page.elements.filterIsInstance<ReaderElement.Rule>()
                .filter(ReaderElement.Rule::overlayStyledUnderline)
                .map(::ReaderRuleDrawCommand),
        )
    }
}

/**
 * 荧光色带：铺满行盒下半行的填充矩形，不是描边。
 *
 * 只有颜色和羽化有意义——线宽/偏移对色带没有意义（见 `underlineControlSupport`）。
 * 两端**统一收 [READER_UNDERLINE_CORNER_DP] 的小圆角**，没有开关：方角边生硬，
 * 半圆又太圆。圆角切在矩形内部、绝不外扩。
 *
 * 羽化把**四边**化开，与描边（`ReaderUnderlineDrawCommand`）共用 `ComposeShader`
 * 的做法，但剖面形状不同：色带是「一块填充」，羽化只该软化边缘、中间仍是实心
 * 色块，所以用 `featherEdgeStops` 的上升沿 + 平台 + 下降沿，而不是中心对称的高斯。
 *
 * 透明度完全由所选颜色的 alpha 决定，不额外压暗（对照 `MarkingEffect.toStyle`）。
 */
internal class ReaderHalfHighlightDrawCommand(
    private val bounds: ReaderRect,
    private val underline: ReaderUnderline,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val featherPx = underline.featherPx.coerceAtLeast(0f)

    fun draw(canvas: Canvas) {
        val bandTop = bounds.top + bounds.height * READER_HALF_HIGHLIGHT_TOP_RATIO
        val bandHeight = (bounds.bottom - bandTop).coerceAtLeast(0f)
        if (bandHeight <= 0f || bounds.right <= bounds.left) return
        val cornerRadius = READER_UNDERLINE_CORNER_DP.dpToPx()
        .coerceAtMost(bandHeight / 2f)
        val color = underline.colorArgb

        val feather = if (underline.featherEffective) featherPx else 0f
        // 垂直：上下两边缘化开，中间保持实心
        val (vPos, vAlpha) = featherEdgeStops(feather, bandHeight)
        val vertical = LinearGradient(
            0f, bandTop, 0f, bounds.bottom,
            featherEdgeColors(vAlpha, color),
            vPos,
            Shader.TileMode.CLAMP,
        )

        // 水平：两端化开，宽度与垂直同量（各向同性）；`featherEdgeStops` 内部已把
        // 上升沿封顶到该维度长度的一半，短段因此保得住中间实心段
        val (hPos, hAlpha) = featherEdgeStops(feather, bounds.width)
        val horizontal = LinearGradient(
            bounds.left, 0f, bounds.right, 0f,
            featherEdgeColors(hAlpha, color),
            hPos,
            Shader.TileMode.CLAMP,
        )

        // DST_IN 让两个方向的 alpha 相乘、颜色取 dst（两者都用同一线色）
        paint.shader = ComposeShader(horizontal, vertical, PorterDuff.Mode.DST_IN)
        canvas.drawRoundRect(
            bounds.left,
            bandTop,
            bounds.right,
            bounds.bottom,
            cornerRadius,
            cornerRadius,
            paint,
        )
        paint.shader = null
    }
}


internal class ReaderRuleDrawCommand(private val rule: ReaderElement.Rule) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = rule.colorArgb
        strokeWidth = rule.widthPx.coerceAtLeast(1f)
        if (rule.dashed) {
            pathEffect = DashPathEffect(
                floatArrayOf(rule.dashOnPx.coerceAtLeast(0.1f), rule.dashOffPx.coerceAtLeast(0.1f)),
                0f,
            )
        }
    }

    fun draw(canvas: Canvas) {
        canvas.drawLine(rule.bounds.left, rule.bounds.top, rule.bounds.right, rule.bounds.bottom, paint)
    }
}

internal class ReaderUnderlineDrawCommand(
    private val bounds: ReaderRect,
    private val underline: ReaderUnderline,
) {
        private val featherPx = underline.featherPx.coerceAtLeast(0f)

        /** 统一的小圆角半径（px），见 [READER_UNDERLINE_CORNER_DP]。 */
        private val cornerRadiusPx = READER_UNDERLINE_CORNER_DP.dpToPx()

        /**
         * 羽化按线型适用性取，不直接读 [ReaderUnderline.featherPx]。
         *
         * 双实线的两条线各自成段、删除线固定在行高比例处、自定义 SVG 的两端由用户
         * 路径决定——这三个线型都不开放柔化，编辑弹层已隐藏对应控件，旧数据里存的
         * 值也不该在正文继续生效。绘制层与裁剪层读同一份 [underlineControlSupport]。
         */
        private val feathered = underline.featherEffective
        /** 自定义 SVG 的两端是用户画的路径，收边会改形状，保持原样。 */
        private val rounded = underline.mode != SVG_MODE
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = underline.colorArgb
            style = Paint.Style.STROKE
        }
        private val svgPath =
            if (underline.mode == SVG_MODE) ReaderSvgPathCache.parse(underline.svgPath) else null
        private val wavePath = if (underline.mode == WAVE_MODE) createWavePath() else null

        /**
         * 羽化的 shader：两个一维高斯剖面相乘，**一趟画完**。
         *
         * 为什么不用多趟叠加：每趟内部 alpha 是常数，合成结果是 N 级阶梯而不是平滑
         * 高斯。密度 3 / 羽化 5dp 时一趟宽度差约 2px，15 趟就是 15 条可见的横线 ——
         * 那正是「一层透明的」加「分界线明显」的来源。叠加还会把边缘区的 alpha
         * 累加到接近 1，看起来像盖了一层透明色，而不是边界化开了。
         *
         * [ComposeShader] 的 `DST_IN` 让两个一维渐变的 alpha 相乘、颜色取 dst，
         * 所以两者用同一个线色、只有 alpha 剖面不同。四个角因此在两个方向一起淡下去，
         * 是真正的各向同性；端点处两个方向的 alpha 都接近 0，既不生硬也不尖。
         *
         * 垂直剖面的采样点由 [featherVerticalStops] 生成（路径处最浓、上下对称）。
         * 波浪额外给一段满浓平台盖住波形起伏（± 峰高）：渐变的浓度中心若锁死在
         * 基线，波峰波谷处的路径点会落到低权重区，波形对比被抹平。水平剖面两端
         * 渐隐、中段满浓。由 [ComposeShader] 一次插值完成，不需要逐趟。
         */
    private fun featherShader(y: Float, halfSpread: Float, plateauHalfPx: Float): Shader {
            val alpha = (underline.colorArgb ushr 24) and 0xFF

            // 水平剖面（dst）：**两端 alpha 0、中间满线色**。
            // DST_IN 的公式是 `α = α_dst × α_src`，dst 的 alpha 是 0 的话乘什么都还是 0 ——
            // 整条线全透明。这里若只给「剥掉 alpha 的线色」，正好就是那个 0。
            val edgePos = edgeFadeRatio(featherPx, bounds.width)
            val horizontal = LinearGradient(
                bounds.left, 0f, bounds.right, 0f,
                intArrayOf(0, underline.colorArgb, underline.colorArgb, 0),
                floatArrayOf(0f, edgePos, 1f - edgePos, 1f),
                Shader.TileMode.CLAMP,
            )

            // 垂直剖面：路径处最浓、向上向下对称衰减到描边带两缘。渐变范围是
            // ±(平台 + 半扩散)：平台盖住波形起伏区（非波浪为 0，退化为中心峰），
            // 两侧各留一个羽化半径做衰减。旧实现把单调剖面直接铺满整带，上缘
            // 满浓生硬、下缘几乎透明（「只有下方模糊」）；必须用中心对称的
            // [featherVerticalStops]。同样用线色而非剥 alpha 的颜色：ComposeShader
            // 两个构造的参数顺序相反（谁当 dst 会调换），两种顺序都正确才不依赖
            // 调用形式。
            val halfSpan = halfSpread + plateauHalfPx
            val (vPositions, vWeights) = featherVerticalStops(plateauHalfPx / halfSpan)
            val verticalColors = IntArray(vWeights.size) { i ->
                val a = (alpha * vWeights[i]).toInt().coerceIn(0, 255)
                (underline.colorArgb and 0x00FFFFFF) or (a shl 24)
            }
            val vertical = LinearGradient(
                0f, y - halfSpan, 0f, y + halfSpan,
                verticalColors,
                vPositions,
                Shader.TileMode.CLAMP,
            )

            return ComposeShader(horizontal, vertical, PorterDuff.Mode.DST_IN)
        }

        fun draw(canvas: Canvas) {
            val y = underline.referenceCenterY(bounds)
            // 描边宽度就是模糊后的总高度：线芯 + 上下各一个羽化半径
            val strokeWidth = if (feathered) {
                featherSpreadPx(underline.widthPx, featherPx) * 2f
            } else {
                finalStrokeWidthPx(underline.widthPx)
            }
            paint.strokeWidth = strokeWidth
            // 波浪的满浓平台 = 实际峰高（quad 控制点的一半）：平台盖住波形起伏区，
            // 路径各点的浓度与基线处一致，波形对比不被锁在基线的渐变压暗。
            val plateauHalfPx =
                if (feathered && underline.mode == WAVE_MODE) {
                    underline.waveControlOffsetPx.coerceAtLeast(0f) / 2f
                } else {
                    0f
                }
            val capInset =
                if (rounded) underline.capInsetPx(strokeWidth, cornerRadiusPx) else 0f
            val left = bounds.left + capInset
            val right = bounds.right - capInset
            paint.strokeCap = if (rounded) Paint.Cap.ROUND else Paint.Cap.BUTT
            paint.shader =
                if (feathered && bounds.right > bounds.left) {
                    featherShader(y, strokeWidth / 2f, plateauHalfPx)
                } else {
                    null
                }
            if (left < right) {
                when (underline.mode) {
                    DASH_MODE -> drawDashed(canvas, left, right, y)
                    WAVE_MODE -> wavePath?.let { canvas.drawPath(it, paint) }
                    DOUBLE_LINE_MODE -> drawDoubleLine(canvas, left, right, y)
                    SVG_MODE -> drawSvg(canvas, y)
                    else -> canvas.drawLine(left, y, right, y, paint)
                }
            } else {
                // 线太短，退化为一个点
                canvas.drawPoint((bounds.left + bounds.right) / 2f, y, paint)
            }
            paint.shader = null
        }

        /**
         * 双实线：第二条在净间隙 + 线芯宽度处，偏移口径来自
         * [doubleLineSecondOffsetPx]。缺了这个分支，正文与高亮规则的双实线
         * 都会掉进 else 塌成单实线。两条线共用同一 paint（圆头与内缩已在
         * [draw] 里按描边宽度算好）。
         */
        private fun drawDoubleLine(canvas: Canvas, start: Float, end: Float, y: Float) {
            val secondY = y + doubleLineSecondOffsetPx(underline.doubleLineGapPx, underline.widthPx)
            canvas.drawLine(start, y, end, y, paint)
            canvas.drawLine(start, secondY, end, secondY, paint)
        }

        /** 虚线：段长与间隙按本段宽度均摊，段尾不再被截出碎段。 */
        private fun drawDashed(canvas: Canvas, start: Float, end: Float, y: Float) {
            val (periods, on, off) = scaledDashSegments(
                end - start,
                underline.dashOnPx,
                underline.dashOffPx,
            )
            for (i in 0 until periods) {
                val segStart = start + i * (on + off)
                if (segStart >= end) break
                canvas.drawLine(segStart, y, (segStart + on).coerceAtMost(end), y, paint)
            }
        }

        /** 自定义 SVG：按 viewBox 宽横向拉伸、基线对齐到 y，与历史实现同一套变换。 */
        private fun drawSvg(canvas: Canvas, y: Float) {
            val path = svgPath?.takeIf { bounds.right > bounds.left } ?: return
            canvas.save()
            canvas.translate(bounds.left, y - READER_SVG_BASELINE_Y)
            canvas.scale((bounds.right - bounds.left) / READER_SVG_BASE_WIDTH, 1f)
            canvas.drawPath(path, paint)
            canvas.restore()
        }

        /**
         * 波浪节点来自共享几何（[waveHalfWaves]），只在构造时算一次：模糊只有一趟，
         * 端部柔和由 shader 的水平剖面负责，波形与波长不必逐趟重排（那会让各趟波长
         * 不同、叠成交错重影）。
         */
        private fun createWavePath(): Path? {
            val y = underline.referenceCenterY(bounds)
            val halfWaves = waveHalfWaves(
                bounds.left,
                bounds.right,
                underline.waveHalfWavePx,
                underline.waveControlOffsetPx,
            ).takeIf { it.isNotEmpty() } ?: return null
            return Path().apply {
                moveTo(halfWaves.first().startX, y)
                halfWaves.forEach { wave ->
                    quadTo(
                        (wave.startX + wave.endX) / 2f,
                        y + wave.controlOffsetY,
                        wave.endX,
                        y,
                    )
                }
            }
        }

    companion object {

        /**
         * 从显式几何构造一条绘制命令，供**正文之外的渲染方**（高亮规则预览、笔记列表）
         * 复用同一条绘制路径。
         *
         * 预览此前自己实现了一套多趟羽化叠加，与正文反复分叉：alpha 是否 8bit 量化、
         * 渐变在 premultiplied 还是 unpremultiplied 空间插值、每趟线宽基准抬不抬下限、
         * 端部渐隐长度取 1.5 倍还是 2 倍——每一项不同都会在 7~24 趟叠加后放大成
         * 肉眼可辨的深浅差。改成构造同一条命令后，这些差异在物理上不可能存在。
         *
         * [y] 是基线（正文是 `bounds.bottom + offsetPx`），这里直接传绝对值：
         * 预览的行盒几何与正文不同，用 bottom 反推会差一个行距。
         *
         * 只支持羽化适用的线型（[underlineControlSupport] 排除 mode 4/5/6）。这些线型
         * 只读 [ReaderRect] 的 left/right/bottom——唯一用到 height 的删除线
         * （`bounds.top + height * 0.52`）走不到这里，故无需传行高。
         */
        fun forSegment(
            mode: Int,
            colorArgb: Int,
            widthPx: Float,
            startX: Float,
            endX: Float,
            y: Float,
            featherPx: Float,
            dashOnPx: Float = 8f,
            dashOffPx: Float = 5f,
            svgPath: String = "",
            waveControlOffsetPx: Float = 3f,
            waveHalfWavePx: Float = 12f,
        ): ReaderUnderlineDrawCommand {
            val underline = ReaderUnderline(
                mode = mode,
                colorArgb = colorArgb,
                widthPx = widthPx,
                // 绘制命令只用 offsetPx 推基线（bounds.bottom + offsetPx）；
                // 这里让 bottom 落在传入的 y 上、offset 归零，保持绝对基线与调用方一致
                offsetPx = 0f,
                svgPath = svgPath,
                dashOnPx = dashOnPx,
                dashOffPx = dashOffPx,
                waveControlOffsetPx = waveControlOffsetPx,
                waveHalfWavePx = waveHalfWavePx,
                doubleLineGapPx = READER_DOUBLE_LINE_GAP_DP.dpToPx(),
                featherPx = featherPx,
            )
            return ReaderUnderlineDrawCommand(
                bounds = ReaderRect(startX, y, endX, y),
                underline = underline,
            )
        }

    }
}

internal object ReaderSvgPathCache {
    private val cache = LruCache<String, Path>(32)

    fun parse(pathData: String): Path? {
        if (pathData.isBlank()) return null
        cache.get(pathData)?.let { return it }
        val path = runCatching { PathParser.createPathFromPathData(pathData) }.getOrNull() ?: return null
        cache.put(pathData, path)
        return path
    }

    fun clear() = cache.evictAll()
}
