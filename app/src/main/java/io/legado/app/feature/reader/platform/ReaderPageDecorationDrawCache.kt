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
import io.legado.app.feature.reader.core.style.READER_HALF_HIGHLIGHT_TOP_RATIO
import io.legado.app.feature.reader.core.style.READER_STRIKE_HEIGHT_RATIO
import io.legado.app.feature.reader.core.style.READER_SVG_BASE_WIDTH
import io.legado.app.feature.reader.core.style.READER_SVG_BASELINE_Y
import io.legado.app.feature.reader.core.style.featherBandPasses
import io.legado.app.feature.reader.core.style.featherGaussian
import io.legado.app.feature.reader.core.style.featherPassCount
import io.legado.app.feature.reader.core.style.scaledDashSegments
import io.legado.app.feature.reader.core.style.waveHalfWaves
import io.legado.app.utils.dpToPx

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
 * 只有颜色、圆头和羽化有意义——线宽/偏移对色带没有意义（见
 * `underlineControlSupport`）。圆头把两端做成半圆（半径 = 色带高度的一半）；
 * 羽化用向内收缩的多趟叠加让边缘渐隐，权重与描边羽化共用 [featherBandPasses]。
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
        // 圆头与柔化取 underline 上的生效值（已按线型适用性过滤），不直接读原始字段：
        // 某线型不开放某个参数时，旧数据里存的值也不该在正文继续生效
        val radius = if (underline.roundCapEffective) bandHeight / 2f else 0f
        val baseAlpha = Color.alpha(underline.colorArgb)
        val rgb = underline.colorArgb and 0x00FFFFFF
        val featherDp = if (underline.featherEffective) featherPx / 1f.dpToPx() else 0f
        // 边缘柔化：向内收缩 + alpha 递减的多趟叠加，硬边化成渐变。
        // 收缩量钳在色带短边的一半以内，窄命中段也不会把矩形收成负数。
        val maxInset = if (featherDp <= 0f) {
            0f
        } else {
            minOf(featherDp.dpToPx(), minOf(bandHeight, bounds.width) / 2f)
        }
        featherBandPasses(featherDp).forEach { pass ->
            val alpha = (baseAlpha * pass.alphaScale).toInt().coerceIn(0, 255)
            if (alpha <= 0) return@forEach
            val inset = pass.insetFactor * maxInset
            paint.color = rgb or (alpha shl 24)
            canvas.drawRoundRect(
                bounds.left + inset,
                bandTop + inset,
                bounds.right - inset,
                bounds.bottom - inset,
                radius,
                radius,
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
     * 圆头与羽化按线型适用性取，不直接读underline。
     *
     * 双下划线的两条线各自成段，圆头会吃掉两线间距；删除线固定在行高比例处，
     * 两者只会让它看起来像渲染错误；自定义 SVG 的"两端"是用户画的路径，无从
     * 定义柔边（且渐变端点还会被 translate/scale 带偏）。这些线型在编辑弹层已
     * 隐藏对应控件，旧数据里存的值也不该在正文继续生效——渲染层与 UI 读同一份
     * [underlineControlSupport]，才不会再次走偏。
     *
     * 判定直接取 [ReaderUnderline] 上的派生属性，绘制层与裁剪层共用同一份口径。
     */
    private val feathered = underline.featherEffective
    private val roundCap = underline.roundCapEffective
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = underline.colorArgb
        strokeWidth = underline.widthPx.coerceAtLeast(1f)
        style = Paint.Style.STROKE
        // 羽化靠圆头端点做柔边，旧 drawUnderlineSegment 同为 `roundCap || feather > 0`
        strokeCap = if (roundCap || feathered) Paint.Cap.ROUND else Paint.Cap.BUTT
    }
    private val wavePath = if (underline.mode == 3) createWavePath(bounds, underline) else null
    private val svgPath = if (underline.mode == 5) ReaderSvgPathCache.parse(underline.svgPath) else null

    fun draw(canvas: Canvas) {
        if (!feathered) {
            drawShape(canvas, paint.strokeWidth.coerceAtLeast(1f), underline.colorArgb)
            return
        }
        // 羽化：多 pass 叠加、高斯权重的 alpha，从外到内逐层变窄，模拟全边缘柔化；
        // 中心保持满 alpha 形成清晰线芯，端点额外做水平渐隐消除硬切（移植旧 TextLine）。
        val passes = featherPassCount(featherPx / 1f.dpToPx())
        val baseAlpha = Color.alpha(underline.colorArgb)
        val rgb = underline.colorArgb and 0x00FFFFFF
        val featherLen = (featherPx * 1.5f).coerceAtLeast(underline.widthPx)
        val segLen = bounds.right - bounds.left
        val edgePos = if (segLen > 0f) (featherLen / segLen).coerceIn(0f, 0.5f) else 0.5f
        for (i in passes downTo 0) {
            val d = i.toFloat() / passes
            val alpha = (baseAlpha * featherGaussian(d)).toInt().coerceIn(0, 255)
            if (alpha <= 0) continue
            val centerColor = rgb or (alpha shl 24)
            val shader = LinearGradient(
                bounds.left, 0f, bounds.right, 0f,
                intArrayOf(0, centerColor, centerColor, 0),
                floatArrayOf(0f, edgePos, 1f - edgePos, 1f),
                Shader.TileMode.CLAMP,
            )
            drawShape(canvas, underline.widthPx + d * featherPx * 2f, centerColor, shader)
        }
    }

    /**
     * 以 [strokeWidthPx] 描边画一段。圆头端点会向外延伸半个宽度，按线芯宽度向内收缩
     * 以保持总长不变；羽化 pass 的加粗圆头向外扩散。[shader] 仅羽化 pass 传入。
     */
    private fun drawShape(
        canvas: Canvas,
        strokeWidthPx: Float,
        colorArgb: Int,
        shader: LinearGradient? = null,
    ) {
        paint.color = colorArgb
        paint.strokeWidth = strokeWidthPx.coerceAtLeast(1f)
        paint.shader = shader
        val capInset = underline.capInsetPx
        val start = bounds.left + capInset
        val end = bounds.right - capInset
        val y = bounds.bottom + underline.offsetPx
        if (start >= end) {
            // 线太短，退化为一个点/圆
            canvas.drawPoint((bounds.left + bounds.right) / 2f, y, paint)
            paint.shader = null
            return
        }
        when (underline.mode) {
            1 -> canvas.drawLine(start, y, end, y, paint)
            2 -> drawDashed(canvas, start, end, y)
            3 -> {
                val path = if (capInset > 0f) {
                    createWavePath(
                        ReaderRect(start, bounds.top, end, bounds.bottom),
                        underline,
                    )
                } else {
                    wavePath
                }
                path?.let { canvas.drawPath(it, paint) }
            }
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
            6 -> canvas.drawLine(
                start,
                bounds.top + bounds.height * READER_STRIKE_HEIGHT_RATIO,
                end,
                bounds.top + bounds.height * READER_STRIKE_HEIGHT_RATIO,
                paint
            )
        }
        paint.shader = null
    }

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

    private companion object {

        /**
         * 波浪节点来自共享几何（[waveHalfWaves]），正文、选中样式预览与规则预览
         * 共用同一份均摊与收口逻辑，避免三处各自写死振幅/波长。
         */
        fun createWavePath(bounds: ReaderRect, underline: ReaderUnderline): Path? {
            val y = bounds.bottom + underline.offsetPx
            val halfWaves = waveHalfWaves(
                bounds.left,
                bounds.right,
                underline.waveHalfWavePx,
                underline.waveControlOffsetPx,
            ).takeIf { it.isNotEmpty() } ?: return null
            return Path().apply {
                moveTo(halfWaves.first().startX, y)
                halfWaves.forEach { wave ->
                    quadTo((wave.startX + wave.endX) / 2f, y + wave.controlOffsetY, wave.endX, y)
                }
            }
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
