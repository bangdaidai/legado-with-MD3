package io.legado.app.feature.reader.platform

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.LruCache
import androidx.core.graphics.PathParser
import io.legado.app.feature.reader.core.model.ReaderElement
import io.legado.app.feature.reader.core.model.ReaderPage
import io.legado.app.feature.reader.core.model.ReaderRect
import io.legado.app.feature.reader.core.model.ReaderUnderline
import io.legado.app.feature.reader.core.model.underlineRuns
import io.legado.app.feature.reader.core.style.READER_DOUBLE_LINE_GAP_DP
import io.legado.app.feature.reader.core.style.READER_EDGE_FADE_SEG_RATIO
import io.legado.app.feature.reader.core.style.READER_HALF_HIGHLIGHT_TOP_RATIO
import io.legado.app.feature.reader.core.style.READER_SVG_BASE_WIDTH
import io.legado.app.feature.reader.core.style.READER_SVG_BASELINE_Y
import io.legado.app.feature.reader.core.style.READER_UNDERLINE_CORNER_DP
import io.legado.app.feature.reader.core.style.bandFeatherMaxInsetPx
import io.legado.app.feature.reader.core.style.featherBandPasses
import io.legado.app.feature.reader.core.style.featherPassArgb
import io.legado.app.feature.reader.core.style.featherPassCount
import io.legado.app.feature.reader.core.style.finalStrokeWidthPx
import io.legado.app.feature.reader.core.style.scaledDashSegments
import io.legado.app.feature.reader.core.style.waveHalfWaves
import io.legado.app.utils.dpToPx
import kotlin.math.roundToInt

/** 线型编号与 `HighlightRule.underlineMode` / `ReaderUnderline.mode` 一致。 */
private const val DASH_MODE = 2
private const val WAVE_MODE = 3

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
 * 半圆又太圆，而同一份样式有两套形态更难预期。圆角切在矩形内部、绝不外扩。
 *
 * 羽化用向内收缩的多趟叠加让**四边**一起化开，权重与描边羽化共用
 * [featherBandPasses]——描边侧（`ReaderUnderlineDrawCommand`）也是同一套几何。
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
        val cornerRadius = READER_UNDERLINE_CORNER_DP.dpToPx().coerceAtMost(bandHeight / 2f)
        val featherDp = if (underline.featherEffective) featherPx / 1f.dpToPx() else 0f
        // 边缘柔化：同一矩形从最虚画到最实（顺序与描边羽化一致），靠 alpha 叠加化开边界。
        // 内缩量按羽化半径取，并受色带短边与段宽约束，避免半行高的色带被吃掉一大半、
        // 以及窄命中段被内缩成「两端内陷」。
        val maxInset = bandFeatherMaxInsetPx(featherDp.dpToPx(), bandHeight, bounds.width)
        featherBandPasses(featherDp).forEach { pass ->
            val passArgb = pass.argb(underline.colorArgb)
            if (Color.alpha(passArgb) <= 0) return@forEach
            val inset = pass.insetFactor * maxInset
            paint.color = passArgb
            canvas.drawRoundRect(
                bounds.left + inset,
                bandTop + inset,
                bounds.right - inset,
                bounds.bottom - inset,
                // 内缩会吃掉圆角：两者同步收缩，否则内缩过头后圆角互相重叠
                (cornerRadius - inset).coerceAtLeast(0f),
                (cornerRadius - inset).coerceAtLeast(0f),
                paint,
            )
        }
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

    /**
     * 统一的小圆角半径（px）。所有线型共用，不再有「端点圆角」开关。
     *
     * 收进上限由 [ReaderUnderline.capInsetPx] 保证：圆角最多半个线宽，外圈那些
     * 加粗到 `widthPx + 2 × featherPx` 的趟若也带圆角，圆角会跟着涨到半个羽化
     * 半径那么远，端头看着像在高亮范围外多接一段。所以圆角只由线芯那一趟承担。
     */
    private val cornerRadiusPx = READER_UNDERLINE_CORNER_DP.dpToPx()

    /**
     * 周期笔形的内缩量全程固定，见 [ReaderUnderline.fixedCapInsetPx]：逐趟变化
     * 会让每趟重新均摊波长/周期，多趟叠起来是一团交错重影。
     */
    private val fixedCapInset = underline.fixedCapInsetPx(cornerRadiusPx)

    /**
     * 羽化按线型适用性取，不直接读 [ReaderUnderline.featherPx]。
     *
     * 双实线的两条线各自成段、删除线固定在行高比例处、自定义 SVG 的两端由用户
     * 路径决定——这三个线型都不开放柔化，编辑弹层已隐藏对应控件，旧数据里存的值
     * 也不该在正文继续生效。绘制层与裁剪层读同一份 [underlineControlSupport]。
     *
     * 端点圆角则**对所有线型无条件生效**（统一小圆角），不走这个判定。
     */
    private val feathered = underline.featherEffective
    /** 自定义 SVG 的两端是用户画的路径，收边/柔化都会改形状，保持原样。 */
    private val rounded = underline.mode != SVG_MODE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = underline.colorArgb
        // 这里存的是线芯宽度（羽化的基准），刻意不抬到 1px：羽化是「基准 + 扩散量」
        // 多趟叠加，基准被抬高会让扩散量错位、柔边更糊。抬升只发生在 drawShape
        // 里真正描边的那一趟，见 [finalStrokeWidthPx]。
        strokeWidth = underline.widthPx
        style = Paint.Style.STROKE
    }
    private val svgPath = if (underline.mode == 5) ReaderSvgPathCache.parse(underline.svgPath) else null

    fun draw(canvas: Canvas) {
        // 零宽段不能进羽化分支：LinearGradient 的零长度坐标轴会直接抛异常，
        // 交给 drawShape 的退化路径画成一个点。
        if (!feathered || bounds.right <= bounds.left) {
            drawShape(canvas, underline.widthPx, underline.colorArgb, 0f, true)
            return
        }
        // 羽化 = **各向同性**模糊：每趟是四周同时缩向核心的形状，alpha 按高斯递增。
        //   - 上下：逐趟加粗，半宽从 `widthPx/2 + 羽化半径` 收到线芯
        //   - 左右：逐趟内缩，长度从整段收到「整段 − 2 × 羽化半径」
        // 两条合起来上下左右一样糊。只加粗不内缩，端部那条带子的上、下边缘就是
        // 垂直硬线（生硬切口）；只内缩不加粗则上下是刀锋。荧光色带的
        // ReaderHalfHighlightDrawCommand 走的是同一套几何。
        // 每趟颜色走 featherPassArgb —— 预览侧共用，保证两边逐位一致。
        val passes = featherPassCount(featherPx / 1f.dpToPx())
        // 端部柔边宽度 = 羽化半径，与上下方向的扩散量同量（各向同性即各方向等量）。
        // 再按段宽封顶：短高亮段（1~3 字）撑不住，两端柔边会重叠成纺锤形。
        val edgeSoft =
            featherPx.coerceAtMost((bounds.right - bounds.left) * READER_EDGE_FADE_SEG_RATIO)
        for (i in passes downTo 0) {
            val d = i.toFloat() / passes
            val centerColor = featherPassArgb(underline.colorArgb, d)
            if (Color.alpha(centerColor) <= 0) continue
            // 端部内缩与叠加顺序反向：最虚那趟齐边铺满，最实那趟收成核心，
            // 与荧光色带 FeatherPass.insetFactor 完全同一口径。
            val edgeInset = (1f - d) * edgeSoft
            drawShape(
                canvas,
                underline.widthPx + d * featherPx * 2f,
                centerColor,
                edgeInset,
                isCore = i == 0,
            )
        }
    }

    /**
     * 以 [strokeWidthPx] 描边画一段。[edgeInset] 是端部内缩量（端部柔边靠它，见
     * [draw]）；[isCore] 标记这是不是羽化的线芯那一趟。
     *
     * 统一小圆角由**线芯那一趟**承担，理由是 Android 的 round cap 半径恒等于
     * `strokeWidth / 2`：外圈那些加粗到 `widthPx + 2 × featherPx` 的趟若也带
     * 圆角，圆角半径就是半个羽化半径那么远（宽度 1dp / 羽化 5dp 时约 5.5dp），
     * 端头看着像在高亮范围外多接一段。外圈是模糊的一部分，本就不该有端点。
     *
     * 虚线与波浪按**固定**内缩量收边：形状由段宽决定（周期、波长都在段宽上
     * 均摊），逐趟变化等于每趟重排一次，叠起来是一团交错重影。见
     * [ReaderUnderline.fixedCapInsetPx]。
     *
     * 线宽在这里才抬到 [finalStrokeWidthPx]：亚像素线宽在低密度屏上几乎不可见，
     * 但羽化的基准宽度不能抬（见 paint 初始化处的注释）。与笔记列表
     * `MarkingStyledText` 共用同一份宽度口径，保证同一条笔记两处粗细一致。
     */
    private fun drawShape(
        canvas: Canvas,
        strokeWidthPx: Float,
        colorArgb: Int,
        edgeInset: Float,
        isCore: Boolean,
    ) {
        paint.color = colorArgb
        paint.strokeWidth = finalStrokeWidthPx(strokeWidthPx)
        paint.strokeCap = if (isCore && rounded) Paint.Cap.ROUND else Paint.Cap.BUTT
        val capInset = when {
            // 周期笔形：形状由段宽决定，全程同一内缩量
            underline.mode == DASH_MODE || underline.mode == WAVE_MODE -> fixedCapInset
            !rounded -> 0f
            else -> underline.capInsetPx(paint.strokeWidth, cornerRadiusPx)
        }
        val y = underline.referenceCenterY(bounds)
        when (underline.mode) {
            DASH_MODE -> drawDashed(
                canvas,
                bounds.left + capInset + edgeInset,
                bounds.right - capInset - edgeInset,
                y,
                bounds.left + fixedCapInset,
                bounds.right - fixedCapInset,
            )
            WAVE_MODE -> wavePath(bounds, capInset, edgeInset)?.let { canvas.drawPath(it, paint) }
            else -> {
                val start = bounds.left + capInset + edgeInset
                val end = bounds.right - capInset - edgeInset
                if (start >= end) {
                    // 线太短，退化为一个点/圆
                    canvas.drawPoint((bounds.left + bounds.right) / 2f, y, paint)
                } else {
                    when (underline.mode) {
                        1 -> canvas.drawLine(start, y, end, y, paint)
                        4 -> {
                            canvas.drawLine(start, y, end, y, paint)
                            val secondY = y + underline.doubleLineGapPx + underline.widthPx
                            canvas.drawLine(start, secondY, end, secondY, paint)
                        }
                        5 -> svgPath?.takeIf { bounds.right > bounds.left }?.let { path ->
                            canvas.save()
                            canvas.translate(bounds.left, y - READER_SVG_BASELINE_Y)
                            canvas.scale((bounds.right - bounds.left) / READER_SVG_BASE_WIDTH, 1f)
                            canvas.drawPath(path, paint)
                            canvas.restore()
                        }
                        else -> canvas.drawLine(start, y, end, y, paint)
                    }
                }
            }
        }
    }

    /**
     * 虚线：周期按**未内缩的整段**均摊，各趟只裁掉超出 [start, end] 的部分。
     *
     * 若跟着内缩后的长度重新均摊，每趟的段长/间隙都不同，虚线会「呼吸」成
     * 一团交错重影。位置固定、只裁两端，端部才有柔边又没有重影。
     */
    private fun drawDashed(
        canvas: Canvas,
        start: Float,
        end: Float,
        y: Float,
        refStart: Float,
        refEnd: Float,
    ) {
        if (refEnd <= refStart) return
        val (periods, on, off) = scaledDashSegments(
            refEnd - refStart,
            underline.dashOnPx,
            underline.dashOffPx,
        )
        for (i in 0 until periods) {
            val segStart = refStart + i * (on + off)
            if (segStart >= end) break
            val from = segStart.coerceAtLeast(start)
            val to = (segStart + on).coerceAtMost(refEnd, end)
            if (to <= from) continue
            canvas.drawLine(from, y, to, y, paint)
        }
    }

    /**
     * 波浪路径。[trimPx] 是端部内缩量，**量化到半波长的整数倍**后再取节点子序列。
     *
     * 直接用内缩后的区间重新均摊波长，每趟波长都不同 → 多趟叠成交错重影。
     * 按整段均摊一次、只丢首尾节点，波长恒定不变。
     */
    private fun wavePath(box: ReaderRect, capInset: Float, trimPx: Float): Path? {
        val left = box.left + capInset
        val right = box.right - capInset
        val all = waveHalfWaves(
            left,
            right,
            underline.waveHalfWavePx,
            underline.waveControlOffsetPx,
        )
        if (all.isEmpty()) return null
        val step = (right - left) / all.size
        val drop = if (step <= 0f) 0 else (trimPx / step).roundToInt()
        val kept = all.drop(drop).dropLast(drop)
        val nodes = if (kept.size >= 2) kept else all
        return Path().apply {
            moveTo(nodes.first().startX, box.bottom + underline.offsetPx)
            nodes.forEach { wave ->
                quadTo(
                    (wave.startX + wave.endX) / 2f,
                    box.bottom + underline.offsetPx + wave.controlOffsetY,
                    wave.endX,
                    box.bottom + underline.offsetPx,
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
