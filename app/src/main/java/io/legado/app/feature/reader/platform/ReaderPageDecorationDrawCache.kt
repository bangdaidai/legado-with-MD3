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
import kotlin.math.roundToInt

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
        const val SVG_BASE_WIDTH = 100f
        const val SVG_BASELINE_Y = 50f

        fun createWavePath(bounds: ReaderRect, underline: ReaderUnderline): Path {
            val y = bounds.bottom + underline.offsetPx
            val width = bounds.right - bounds.left
            // 把余数均摊进所有半波：段尾不再被 coerceAtMost 截出挤扁的短波，
            // 同一行内波长一致，行间疏密也不随余量跳动
            val halfWaves = waveHalfWaveCount(width, underline.waveLengthPx)
            val step = width / halfWaves
            return Path().apply {
                moveTo(bounds.left, y)
                var x = bounds.left
                for (i in 0 until halfWaves) {
                    // 最后一个半波强制收口到区间末端，避免取整误差留下小缝隙
                    val next = if (i == halfWaves - 1) bounds.right else x + step
                    val amplitude =
                        if (i % 2 == 0) -underline.waveAmplitudePx else underline.waveAmplitudePx
                    quadTo((x + next) / 2f, y + amplitude, next, y)
                    x = next
                }
            }
        }
    }
}

/** 周期笔画（波浪/虚线）的最小段长，防止除零和退化配置。 */
internal const val READER_MIN_STROKE_SEGMENT_PX = 0.1f

/** 把 [widthPx] 均摊成整数个半波后的半波数。 */
internal fun waveHalfWaveCount(widthPx: Float, waveLengthPx: Float): Int =
    (widthPx / waveLengthPx.coerceAtLeast(READER_MIN_STROKE_SEGMENT_PX))
        .roundToInt().coerceAtLeast(1)

/**
 * 虚线周期数就近取整后把余量按同比分给段与间隙（段尾不再被硬切成碎段）。
 * 返回 Triple(周期数, 缩放后的段长, 缩放后的间隙)。
 */
internal fun scaledDashSegments(widthPx: Float, dashOnPx: Float, dashOffPx: Float): Triple<Int, Float, Float> {
    val dashOn = dashOnPx.coerceAtLeast(READER_MIN_STROKE_SEGMENT_PX)
    val dashOff = dashOffPx.coerceAtLeast(READER_MIN_STROKE_SEGMENT_PX)
    val periods = ((widthPx + dashOff) / (dashOn + dashOff)).roundToInt().coerceAtLeast(1)
    val scale = widthPx / (periods * dashOn + (periods - 1) * dashOff)
    return Triple(periods, dashOn * scale, dashOff * scale)
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
