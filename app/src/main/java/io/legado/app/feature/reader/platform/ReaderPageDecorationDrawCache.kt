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
                ReaderHalfHighlightDrawCommand(run.bounds, run.underline.colorArgb)
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

internal class ReaderHalfHighlightDrawCommand(
    private val bounds: ReaderRect,
    colorArgb: Int,
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb }

    fun draw(canvas: Canvas) {
        canvas.drawRect(
            bounds.left,
            bounds.top + bounds.height * 0.5f,
            bounds.right,
            bounds.bottom,
            paint,
        )
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
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = underline.colorArgb
        strokeWidth = underline.widthPx.coerceAtLeast(1f)
        style = Paint.Style.STROKE
        // 羽化靠圆头端点做柔边，旧 drawUnderlineSegment 同为 `roundCap || feather > 0`
        strokeCap = if (underline.roundCap || featherPx > 0f) Paint.Cap.ROUND else Paint.Cap.BUTT
    }
    private val wavePath = if (underline.mode == 3) createWavePath(bounds, underline) else null
    private val svgPath = if (underline.mode == 5) ReaderSvgPathCache.parse(underline.svgPath) else null

    fun draw(canvas: Canvas) {
        if (featherPx <= 0f) {
            drawShape(canvas, paint.strokeWidth.coerceAtLeast(1f), underline.colorArgb)
            return
        }
        // 羽化：多 pass 叠加、高斯权重的 alpha，从外到内逐层变窄，模拟全边缘柔化；
        // 中心保持满 alpha 形成清晰线芯，端点额外做水平渐隐消除硬切（移植旧 TextLine）。
        val passes = ((featherPx / 1f.dpToPx()) * 3f).toInt().coerceIn(6, 24)
        val baseAlpha = Color.alpha(underline.colorArgb)
        val rgb = underline.colorArgb and 0x00FFFFFF
        val sigma = 0.55f
        val featherLen = (featherPx * 1.5f).coerceAtLeast(underline.widthPx)
        val segLen = bounds.right - bounds.left
        val edgePos = if (segLen > 0f) (featherLen / segLen).coerceIn(0f, 0.5f) else 0.5f
        for (i in passes downTo 0) {
            val d = i.toFloat() / passes
            val gaussian = kotlin.math.exp(-(d * d) / (2f * sigma * sigma))
            val alpha = (baseAlpha * gaussian).toInt().coerceIn(0, 255)
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
     * 以 [strokeWidthPx] 描边画一段。圆头端点会向外延伸半个宽度，需要向内收缩补偿以保持
     * 总长不变（旧 `drawUnderlineShape` 的 capInset）；[shader] 仅羽化 pass 传入。
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
        val width = paint.strokeWidth
        val capInset = if (paint.strokeCap == Paint.Cap.ROUND) width / 2f else 0f
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
                canvas.translate(bounds.left, y - SVG_BASELINE_Y)
                canvas.scale((bounds.right - bounds.left) / SVG_BASE_WIDTH, 1f)
                canvas.drawPath(path, paint)
                canvas.restore()
            }
            6 -> canvas.drawLine(
                start,
                bounds.top + bounds.height * 0.52f,
                end,
                bounds.top + bounds.height * 0.52f,
                paint
            )
        }
        paint.shader = null
    }

    private fun drawDashed(canvas: Canvas, start: Float, end: Float, y: Float) {
        val dashOn = underline.dashOnPx.coerceAtLeast(MIN_SEGMENT_PX)
        val dashOff = underline.dashOffPx.coerceAtLeast(MIN_SEGMENT_PX)
        var x = start
        while (x < end) {
            canvas.drawLine(x, y, (x + dashOn).coerceAtMost(end), y, paint)
            x += dashOn + dashOff
        }
    }

    private companion object {
        const val MIN_SEGMENT_PX = 0.1f
        const val SVG_BASE_WIDTH = 100f
        const val SVG_BASELINE_Y = 50f

        fun createWavePath(bounds: ReaderRect, underline: ReaderUnderline): Path {
            val y = bounds.bottom + underline.offsetPx
            val waveLength = underline.waveLengthPx.coerceAtLeast(MIN_SEGMENT_PX)
            return Path().apply {
                moveTo(bounds.left, y)
                var x = bounds.left
                while (x < bounds.right) {
                    val next = (x + waveLength).coerceAtMost(bounds.right)
                    quadTo((x + next) / 2f, y - underline.waveAmplitudePx, next, y)
                    x = next
                    if (x < bounds.right) {
                        val nextDown = (x + waveLength).coerceAtMost(bounds.right)
                        quadTo((x + nextDown) / 2f, y + underline.waveAmplitudePx, nextDown, y)
                        x = nextDown
                    }
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
