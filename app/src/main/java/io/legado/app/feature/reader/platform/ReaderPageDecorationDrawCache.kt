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
import io.legado.app.feature.reader.core.style.READER_HALF_HIGHLIGHT_TOP_RATIO
import io.legado.app.feature.reader.core.style.READER_STRIKE_HEIGHT_RATIO
import io.legado.app.feature.reader.core.style.READER_SVG_BASE_WIDTH
import io.legado.app.feature.reader.core.style.READER_SVG_BASELINE_Y
import io.legado.app.feature.reader.core.style.bandFeatherMaxInsetPx
import io.legado.app.feature.reader.core.style.featherBandPasses
import io.legado.app.feature.reader.core.style.featherPassArgb
import io.legado.app.feature.reader.core.style.featherPassCount
import io.legado.app.feature.reader.core.style.finalStrokeWidthPx
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
        val featherDp = if (underline.featherEffective) featherPx / 1f.dpToPx() else 0f
        // 边缘柔化：同一矩形从最虚画到最实（顺序与描边羽化一致），靠 alpha 叠加化开边界。
        // 内缩量按羽化半径取并受色带短边约束，避免半行高的色带被吃掉一大半。
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
                // 圆头开着时半径 = 色带高度一半，内缩会吃掉半径：两者同步收缩才保持
                // 端部半圆的形状，否则内缩过头后圆弧互相重叠
                (radius - inset).coerceAtLeast(0f),
                (radius - inset).coerceAtLeast(0f),
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
     * 双实线的两条线各自成段，圆头会吃掉两线间距；删除线固定在行高比例处，
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
        // 这里存的是线芯宽度（羽化的基准），刻意不抬到 1px：羽化是「基准 + 扩散量」
        // 多趟叠加，基准被抬高会让扩散量错位、柔边更糊。抬升只发生在 drawShape
        // 里真正描边的那一趟，见 [finalStrokeWidthPx]。
        strokeWidth = underline.widthPx
        style = Paint.Style.STROKE
        // 羽化**不**强制圆头：端点形状只看端点圆角开关。旧口径
        // `roundCap || feathered` 在圆角关闭时也用圆弧收头，再叠加端部 alpha
        // 渐隐（alpha=0 点就在段边界），端头就成了被削掉的尖锥——宽度 1dp、
        // 羽化 5dp 时最外那趟宽 11dp，圆弧与渐隐互相打架，肉眼看到的是两头尖。
        // 关闭圆角时用平切口：宽度不收窄，端头只随水平渐隐淡出。
        strokeCap = if (roundCap) Paint.Cap.ROUND else Paint.Cap.BUTT
    }
    private val wavePath = if (underline.mode == 3) createWavePath(bounds, underline) else null
    private val svgPath = if (underline.mode == 5) ReaderSvgPathCache.parse(underline.svgPath) else null

    fun draw(canvas: Canvas) {
        // 零宽段不能进羽化分支：LinearGradient 的零长度坐标轴会直接抛异常，
        // 交给 drawShape 的退化路径画成一个点。
        if (!feathered || bounds.right <= bounds.left) {
            drawShape(canvas, underline.widthPx, underline.colorArgb)
            return
        }
        // 羽化：多 pass 叠加、高斯权重的 alpha，从外到内逐层变窄，模拟全边缘柔化；
        // 中心保持满 alpha 形成清晰线芯，端点额外做水平渐隐消除硬切（移植旧 TextLine）。
        // 每趟颜色走 featherPassArgb —— 预览侧共用，保证两边逐位一致。
        val passes = featherPassCount(featherPx / 1f.dpToPx())
        // 端部渐隐长度 = 上下方向的扩散量（羽化半径 × 2），两端与上下用同一个空间尺度。
        // 此前用 1.5 倍：上下扩散 2 倍、两端只渐隐 1.5 倍，两个尺度打架使四个角被
        // 重复施色、糊成一团，而四边中间相对干净。
        val featherLen = (featherPx * 2f).coerceAtLeast(underline.widthPx)
        for (i in passes downTo 0) {
            val d = i.toFloat() / passes
            val centerColor = featherPassArgb(underline.colorArgb, d)
            if (Color.alpha(centerColor) <= 0) continue
            drawShape(
                canvas,
                underline.widthPx + d * featherPx * 2f,
                centerColor,
                endFadeShader(centerColor, featherLen),
            )
        }
    }

    /**
     * 两端水平渐隐的 shader：段边界处仍是半透明，一路淡到 `±featherLen / 2`。
     *
     * 窗口中心必须**对齐段边界**，而不是像旧实现那样整个落在段内
     * （`[left, left + 2F]`，中心偏到 `left + F`）。圆头的尖端恰好在段边界上，
     * 中心偏一段距离就等于把尖端按在 alpha=0 的位置——圆弧画得再圆，从全透明
     * 长出来的东西看着都是尖的，而且羽化半径越大偏得越远。
     *
     * 圆头关闭时笔形齐边、段外没有任何几何覆盖，窗口留在段内即可（`bleed = 0`）；
     * 开着时圆弧自然探出段外，淡出必须跨过边界才画得出来。
     */
    private fun endFadeShader(colorArgb: Int, featherLen: Float): LinearGradient {
        val bleed = if (roundCap) featherLen / 2f else 0f
        val start = bounds.left - bleed
        val end = bounds.right + bleed
        val span = end - start
        val edgePos = if (span > 0f) (featherLen / span).coerceIn(0f, 0.5f) else 0.5f
        return LinearGradient(
            start, 0f, end, 0f,
            intArrayOf(0, colorArgb, colorArgb, 0),
            floatArrayOf(0f, edgePos, 1f - edgePos, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /**
     * 以 [strokeWidthPx] 描边画一段，起止就是段边界：[strokeWidthPx] 是线芯宽度，
     * 端点圆角开启时由 `strokeCap = ROUND` 自然向外探出半个宽度，不再内缩补偿。
     *
     * 旧实现按线芯宽度向内收缩以"保持总长不变"，代价是圆弧尖端被压在段边界上，
     * 而羽化的端部渐隐恰好在那里归零——尖端落在全透明处，端头看着是尖的，
     * 探出去的部分也画不出来。让圆弧自然伸出反而是正确的：真实模糊会把周围的
     * 颜色渗到端点外，端点不是全透明的。
     *
     * [shader] 仅羽化 pass 传入（自带两端水平渐隐）。线宽在这里才抬到
     * [finalStrokeWidthPx]：亚像素线宽在低密度屏上几乎不可见，但羽化的基准宽度
     * 不能抬（见 paint 初始化处的注释）。与笔记列表 `MarkingStyledText` 共用同一
     * 份宽度口径，保证同一条笔记两处粗细一致。
     */
    private fun drawShape(
        canvas: Canvas,
        strokeWidthPx: Float,
        colorArgb: Int,
        shader: LinearGradient? = null,
    ) {
        paint.color = colorArgb
        paint.strokeWidth = finalStrokeWidthPx(strokeWidthPx)
        paint.shader = shader
        val start = bounds.left
        val end = bounds.right
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
            3 -> wavePath?.let { canvas.drawPath(it, paint) }
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
            roundCap: Boolean,
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
                roundCap = roundCap,
                featherPx = featherPx,
            )
            return ReaderUnderlineDrawCommand(
                bounds = ReaderRect(startX, y, endX, y),
                underline = underline,
            )
        }

        /**
         * 波浪节点来自共享几何（[waveHalfWaves]），正文、选中样式预览与规则预览
         * 共用同一份均摊与收口逻辑，避免三处各自写死振幅/波长。
         */
        private fun createWavePath(bounds: ReaderRect, underline: ReaderUnderline): Path? {
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
