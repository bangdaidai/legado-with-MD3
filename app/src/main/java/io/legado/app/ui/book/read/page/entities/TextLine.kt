package io.legado.app.ui.book.read.page.entities

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Build
import androidx.annotation.Keep
import io.legado.app.help.PaintPool
import io.legado.app.help.book.isImage
import io.legado.app.help.highlight.NinePatchDrawHelper
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.ResourceLoadFailureCache
import io.legado.app.ui.book.read.page.entities.TextPage.Companion.emptyTextPage
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.canvasrecorder.recordIfNeededThenDraw
import io.legado.app.utils.dpToPx
import splitties.init.appCtx

/**
 * 行信息
 */
@Keep
@Suppress("unused", "MemberVisibilityCanBePrivate")
data class TextLine(
    var text: String = "",
    private val textColumns: ArrayList<BaseColumn> = arrayListOf(),
    var lineTop: Float = 0f,
    var lineBase: Float = 0f,
    var lineBottom: Float = 0f,
    var indentWidth: Float = 0f,
    var paragraphNum: Int = 0,
    var chapterPosition: Int = 0,
    var pagePosition: Int = 0,
    val isTitle: Boolean = false,
    var titleTextSize: Float? = null,
    var isParagraphEnd: Boolean = false,
    var isImage: Boolean = false,
    var isHtml: Boolean = false,
    var startX: Float = 0f,
    var indentSize: Int = 0,
    var extraLetterSpacing: Float = 0f,
    var extraLetterSpacingOffsetX: Float = 0f,
    var wordSpacing: Float = 0f,
    var exceed: Boolean = false,
    var onlyTextColumn: Boolean = true,
) {

    val columns: List<BaseColumn> get() = textColumns
    val charSize: Int get() = text.length
    val lineStart: Float get() = textColumns.firstOrNull()?.start ?: 0f
    val lineEnd: Float get() = textColumns.lastOrNull()?.end ?: 0f
    val chapterIndices: IntRange get() = chapterPosition..chapterPosition + charSize
    val height: Float inline get() = lineBottom - lineTop
    val canvasRecorder = CanvasRecorderFactory.create()
    var searchResultColumnCount = 0
    var isReadAloud: Boolean = false
        set(value) {
            if (field != value) {
                invalidate()
            }
            if (value) {
                textPage.hasReadAloudSpan = true
            }
            field = value
        }
    var textPage: TextPage = emptyTextPage
    var isLeftLine = true
    val useUnderline: Boolean
        get() = ChapterProvider.renderStyle.useUnderline

    fun addColumn(column: BaseColumn) {
        if (column !is TextColumn) {
            onlyTextColumn = false
        } else if (column.textColor != null || column.bgColor != null || column.underlineMode != 0 || column.bgImage.isNotEmpty() || column.fontPath.isNotEmpty() || column.fontWeight != 400 || column.isItalic) {
            onlyTextColumn = false
        }
        column.textLine = this
        textColumns.add(column)
    }

    fun addColumns(columns: Collection<BaseColumn>) {
        onlyTextColumn = false
        columns.forEach { column ->
            column.textLine = this
        }
        textColumns.addAll(columns)
    }

    fun getColumn(index: Int): BaseColumn {
        return textColumns.getOrElse(index) {
            textColumns.last()
        }
    }

    fun getColumnReverseAt(index: Int, offset: Int = 0): BaseColumn {
        return textColumns[textColumns.lastIndex - offset - index]
    }

    fun getColumnsCount(): Int {
        return textColumns.size
    }

    fun upTopBottom(durY: Float, textHeight: Float, fontMetrics: android.graphics.Paint.FontMetrics) {
        lineTop = ChapterProvider.paddingTop + durY
        lineBottom = lineTop + textHeight
        lineBase = lineBottom - fontMetrics.descent
    }

    fun isTouch(x: Float, y: Float, relativeOffset: Float): Boolean {
        return y > lineTop + relativeOffset
                && y < lineBottom + relativeOffset
                && x >= lineStart
                && x <= lineEnd
    }

    fun isTouchY(y: Float, relativeOffset: Float): Boolean {
        return y > lineTop + relativeOffset
                && y < lineBottom + relativeOffset
    }

    fun isVisible(relativeOffset: Float): Boolean {
        val top = lineTop + relativeOffset
        val bottom = lineBottom + relativeOffset
        val width = bottom - top
        val visibleTop = ChapterProvider.paddingTop
        val visibleBottom = ChapterProvider.visibleBottom
        val visible = when {
            top >= visibleTop && bottom <= visibleBottom -> true
            top <= visibleTop && bottom >= visibleBottom -> true
            top < visibleTop && bottom > visibleTop && bottom < visibleBottom -> {
                if (isImage) {
                    true
                } else {
                    val visibleRate = (bottom - visibleTop) / width
                    visibleRate > 0.6
                }
            }
            top > visibleTop && top < visibleBottom && bottom > visibleBottom -> {
                if (isImage) {
                    true
                } else {
                    val visibleRate = (visibleBottom - top) / width
                    visibleRate > 0.6
                }
            }
            else -> false
        }
        return visible
    }

    fun draw(view: ContentTextView, canvas: Canvas) {
        if (ChapterProvider.renderStyle.optimizeRender) {
            canvasRecorder.recordIfNeededThenDraw(canvas, view.width, height.toInt()) {
                drawTextLine(view, this)
            }
        } else {
            drawTextLine(view, canvas)
        }
    }

    private fun drawTextLine(view: ContentTextView, canvas: Canvas) {
        drawStyledBackgrounds(canvas)
        drawBgColors(canvas)
        drawStyledUnderlines(canvas, belowText = true)
        if (checkFastDraw()) {
            fastDrawTextLine(view, canvas)
        } else {
            for (i in columns.indices) columns[i].draw(view, canvas)
        }

        if (useUnderline && (isReadAloud || searchResultColumnCount > 0)) {
            val linePaint = ChapterProvider.linePaint
            val lineY = height - 1.dpToPx()
            canvas.drawLine(lineStart + indentWidth, lineY, lineEnd, lineY, linePaint)
        }

        drawStyledUnderlines(canvas, belowText = false)

        val renderStyle = ChapterProvider.renderStyle
        if (renderStyle.underline && !isImage && ReadBook.book?.isImage != true) {
            drawUnderline(canvas, renderStyle)
        }
    }

    @SuppressLint("NewApi")
    private fun fastDrawTextLine(view: ContentTextView, canvas: Canvas) {
        val textPaint = if (isTitle) {
            ChapterProvider.titlePaint
        } else {
            ChapterProvider.contentPaint
        }
        val renderStyle = ChapterProvider.renderStyle
        val textColor = if (isReadAloud) {
            renderStyle.textAccentColor
        } else if (isTitle && renderStyle.titleColor != 0) {
            renderStyle.titleColor
        } else {
            renderStyle.textColor
        }
        if (textPaint.color != textColor) {
            textPaint.color = textColor
        }
        val paint = PaintPool.obtain()
        paint.set(textPaint)
        val letterSpacing = paint.letterSpacing * paint.textSize
        val letterSpacingHalf = letterSpacing * 0.5f
        if (extraLetterSpacing != 0f) {
            paint.letterSpacing += extraLetterSpacing
        }
        if (wordSpacing != 0f) {
            paint.wordSpacing = wordSpacing
        }
        val offsetX = if (atLeastApi35) letterSpacingHalf else extraLetterSpacingOffsetX
        canvas.drawText(text, indentSize, text.length, startX + offsetX, lineBase - lineTop, paint)
        PaintPool.recycle(paint)
        for (i in columns.indices) {
            val column = columns[i] as TextColumn
            if (column.selected) {
                canvas.drawRect(column.start, 0f, column.end, height, view.selectedPaint)
            }
        }
    }

    /**
     * 绘制下划线
     */
    private fun drawUnderline(canvas: Canvas, renderStyle: ChapterProvider.RenderStyle) {
        val paint = PaintPool.obtain()
        paint.set(ChapterProvider.contentPaint)
        paint.clearShadowLayer()
        paint.color = renderStyle.underlineColor
        paint.strokeWidth = renderStyle.underlineHeight.toFloat()
        paint.style = Paint.Style.STROKE
        paint.pathEffect = if (renderStyle.dottedLine && !renderStyle.isEInkMode)
            ChapterProvider.dashEffect
        else
            null

        val lineY = height + (renderStyle.underlinePadding - 10).dpToPx()
        val pageOffset = if (textPage.doublePage && !isLeftLine) {
            ChapterProvider.viewWidth / 2f
        } else {
            0f
        }
        val startX = if (renderStyle.underlineExtend) {
            pageOffset + ChapterProvider.paddingLeft
        } else {
            lineStart + indentWidth
        }
        val endX = if (renderStyle.underlineExtend) {
            pageOffset + ChapterProvider.paddingLeft + ChapterProvider.visibleWidth
        } else {
            lineEnd
        }
        canvas.drawLine(startX, lineY, endX, lineY, paint)
        PaintPool.recycle(paint)
    }

    /**
     * 绘制高亮规则匹配文本的背景图
     */
    private fun drawStyledBackgrounds(canvas: Canvas) {
        if (isImage || columns.isEmpty()) return
        if (columns.none { (it as? TextBaseColumn)?.bgImage?.isNotEmpty() == true }) return
        var rangeStart = 0f
        var rangeEnd = 0f
        var currentBgImage = ""
        var currentBgImageFit = 0
        var currentBgImageScale = 1f
        var currentNpLeft = 0.1f
        var currentNpRight = 0.1f
        var currentNpTop = 0.1f
        var currentNpBottom = 0.1f
        var currentBgPadStart = 0f
        var currentBgPadEnd = 0f
        var currentBgPadTop = 0f
        var currentBgPadBottom = 0f
        var active = false
        columns.forEachIndexed { index, column ->
            val textColumn = column as? TextBaseColumn
            val bgImage = textColumn?.bgImage ?: ""
            val bgImageFit = textColumn?.bgImageFit ?: 0
            val bgImageScale = textColumn?.bgImageScale ?: 1f
            val npLeft = textColumn?.npLeft ?: 0.1f
            val npRight = textColumn?.npRight ?: 0.1f
            val npTop = textColumn?.npTop ?: 0.1f
            val npBottom = textColumn?.npBottom ?: 0.1f
            val bgPadStart = textColumn?.bgPadStart ?: 0f
            val bgPadEnd = textColumn?.bgPadEnd ?: 0f
            val bgPadTop = textColumn?.bgPadTop ?: 0f
            val bgPadBottom = textColumn?.bgPadBottom ?: 0f
            when {
                bgImage.isEmpty() && active -> {
                    drawBgImageSegment(canvas, rangeStart, rangeEnd, currentBgImage, currentBgImageFit, currentBgImageScale, currentNpLeft, currentNpRight, currentNpTop, currentNpBottom, currentBgPadStart, currentBgPadEnd, currentBgPadTop, currentBgPadBottom)
                    active = false
                }
                bgImage.isNotEmpty() && !active -> {
                    rangeStart = textColumn!!.start
                    rangeEnd = textColumn.end
                    currentBgImage = bgImage
                    currentBgImageFit = bgImageFit
                    currentBgImageScale = bgImageScale
                    currentNpLeft = npLeft
                    currentNpRight = npRight
                    currentNpTop = npTop
                    currentNpBottom = npBottom
                    currentBgPadStart = bgPadStart
                    currentBgPadEnd = bgPadEnd
                    currentBgPadTop = bgPadTop
                    currentBgPadBottom = bgPadBottom
                    active = true
                }
                bgImage.isNotEmpty() && bgImage == currentBgImage && bgImageFit == currentBgImageFit && bgImageScale == currentBgImageScale && npLeft == currentNpLeft && npRight == currentNpRight && npTop == currentNpTop && npBottom == currentNpBottom -> {
                    rangeEnd = textColumn!!.end
                }
                bgImage.isNotEmpty() -> {
                    drawBgImageSegment(canvas, rangeStart, rangeEnd, currentBgImage, currentBgImageFit, currentBgImageScale, currentNpLeft, currentNpRight, currentNpTop, currentNpBottom, currentBgPadStart, currentBgPadEnd, currentBgPadTop, currentBgPadBottom)
                    rangeStart = textColumn!!.start
                    rangeEnd = textColumn.end
                    currentBgImage = bgImage
                    currentBgImageFit = bgImageFit
                    currentBgImageScale = bgImageScale
                    currentNpLeft = npLeft
                    currentNpRight = npRight
                    currentNpTop = npTop
                    currentNpBottom = npBottom
                    currentBgPadStart = bgPadStart
                    currentBgPadEnd = bgPadEnd
                    currentBgPadTop = bgPadTop
                    currentBgPadBottom = bgPadBottom
                }
            }
            if (active && index == columns.lastIndex) {
                drawBgImageSegment(canvas, rangeStart, rangeEnd, currentBgImage, currentBgImageFit, currentBgImageScale, currentNpLeft, currentNpRight, currentNpTop, currentNpBottom, currentBgPadStart, currentBgPadEnd, currentBgPadTop, currentBgPadBottom)
            }
        }
    }

    /**
     * 绘制高亮规则匹配文本的背景色
     */
    private fun drawBgColors(canvas: Canvas) {
        if (isImage || columns.isEmpty()) return
        var i = 0
        while (i < columns.size) {
            val col = columns[i] as? TextBaseColumn
            if (col == null) { i++; continue }
            val color = col.bgColor
            if (color == null) { i++; continue }
            val left = col.start
            var right = col.end
            var j = i + 1
            while (j < columns.size) {
                val next = columns[j] as? TextBaseColumn ?: break
                if (next.bgColor != color) break
                right = next.end
                j++
            }
            val top = 0f
            val bottom = height.toFloat()
            val paint = PaintPool.obtain()
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawRect(left, top, right, bottom, paint)
            PaintPool.recycle(paint)
            i = j
        }
    }

    /**
     * 绘制高亮规则匹配文本的下划线段
     */
    private fun drawStyledUnderlines(canvas: Canvas, belowText: Boolean) {
        if (isImage || columns.isEmpty()) return
        if (columns.none {
            val c = it as? TextBaseColumn
            c?.underlineMode?.let { m -> m != 0 } == true &&
                c.underlineBelowText == belowText
        }) return
        var rangeStart = 0f
        var rangeEnd = 0f
        var mode = 0
        var color = 0
        var width = 1f
        var offset = 2f
        var svgPath = ""
        var roundCap = false
        var feather = 0f
        var active = false
        columns.forEachIndexed { index, column ->
            val textColumn = column as? TextBaseColumn
            val currentMode = textColumn?.underlineMode ?: 0
            val currentBelowText = textColumn?.underlineBelowText ?: false
            // 只绘制匹配当前层级（文字上/下）的下划线
            val effectiveMode = if (currentBelowText == belowText) currentMode else 0
            val currentColor = textColumn?.underlineColor
                ?: textColumn?.textColor
                ?: ChapterProvider.renderStyle.textColor
            val currentWidth = textColumn?.underlineWidth ?: 1f
            val currentOffset = textColumn?.underlineOffset ?: 2f
            val currentSvgPath = textColumn?.underlineSvgPath ?: ""
            val currentRoundCap = textColumn?.underlineRoundCap ?: false
            val currentFeather = textColumn?.underlineFeather ?: 0f
            val shouldContinue = active &&
                effectiveMode == mode &&
                currentColor == color &&
                currentWidth == width &&
                currentOffset == offset &&
                currentSvgPath == svgPath &&
                currentRoundCap == roundCap &&
                currentFeather == feather
            when {
                effectiveMode == 0 && active -> {
                    drawUnderlineSegment(canvas, rangeStart, rangeEnd, mode, color, width, offset, svgPath, roundCap, feather)
                    active = false
                }
                effectiveMode != 0 && !active -> {
                    rangeStart = textColumn!!.start
                    rangeEnd = textColumn.end
                    mode = effectiveMode
                    color = currentColor
                    width = currentWidth
                    offset = currentOffset
                    svgPath = currentSvgPath
                    roundCap = currentRoundCap
                    feather = currentFeather
                    active = true
                }
                effectiveMode != 0 && shouldContinue -> {
                    rangeEnd = textColumn!!.end
                }
                effectiveMode != 0 -> {
                    drawUnderlineSegment(canvas, rangeStart, rangeEnd, mode, color, width, offset, svgPath, roundCap, feather)
                    rangeStart = textColumn!!.start
                    rangeEnd = textColumn.end
                    mode = effectiveMode
                    color = currentColor
                    width = currentWidth
                    offset = currentOffset
                    svgPath = currentSvgPath
                    roundCap = currentRoundCap
                    feather = currentFeather
                }
            }
            if (active && index == columns.lastIndex) {
                drawUnderlineSegment(canvas, rangeStart, rangeEnd, mode, color, width, offset, svgPath, roundCap, feather)
            }
        }
    }

    /**
     * 绘制单段下划线
     */
    private fun drawUnderlineSegment(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        underlineMode: Int,
        underlineColor: Int,
        underlineWidth: Float = 1f,
        underlineOffset: Float = 2f,
        svgPathStr: String = "",
        roundCap: Boolean = false,
        feather: Float = 0f,
    ) {
        val paint = PaintPool.obtain()
        paint.set(ChapterProvider.contentPaint)
        paint.clearShadowLayer()
        paint.color = underlineColor
        paint.strokeWidth = underlineWidth.dpToPx()
        paint.style = Paint.Style.STROKE
        if (roundCap || feather > 0f) paint.strokeCap = Paint.Cap.ROUND
        val lineY = height + underlineOffset.dpToPx()
        if (feather > 0f) {
            // 羽化：多 pass 叠加、高斯权重的 alpha，从外到内逐层变窄，模拟全边缘柔化
            // 层数随羽化半径增加以避免断层；不让中心满 alpha，避免硬芯
            val passes = (feather * 3f).toInt().coerceIn(6, 24)
            val baseAlpha = Color.alpha(underlineColor)
            val rgb = underlineColor and 0x00FFFFFF
            // sigma 控制浓度衰减，越小越集中、越大越弥散
            val sigma = 0.5f
            for (i in passes downTo 0) {
                // d: 归一化的到中心距离（0=中心，1=最外层）
                val d = i.toFloat() / passes
                val gaussian = kotlin.math.exp(-(d * d) / (2f * sigma * sigma))
                val alpha = (baseAlpha * gaussian * 0.5f).toInt().coerceIn(0, 255)
                if (alpha <= 0) continue
                paint.color = rgb or (alpha shl 24)
                val passWidth = underlineWidth + d * feather * 2f
                drawUnderlineShape(canvas, paint, startX, endX, lineY, underlineMode, passWidth, underlineWidth, svgPathStr)
            }
        } else {
            drawUnderlineShape(canvas, paint, startX, endX, lineY, underlineMode, underlineWidth, underlineWidth, svgPathStr)
        }
        PaintPool.recycle(paint)
    }

    /**
     * @param strokeWidth 实际描边宽度（dp），羽化时逐层放大
     * @param geomWidth 基准宽度（dp），仅用于双线间距等几何计算
     */
    private fun drawUnderlineShape(
        canvas: Canvas,
        paint: Paint,
        startX: Float,
        endX: Float,
        lineY: Float,
        underlineMode: Int,
        strokeWidth: Float,
        geomWidth: Float,
        svgPathStr: String,
    ) {
        paint.strokeWidth = strokeWidth.dpToPx()
        // 圆头端点会向外延伸半个宽度，需要向内收缩补偿以保持总长不变
        val capInset = if (paint.strokeCap == Paint.Cap.ROUND) strokeWidth.dpToPx() / 2f else 0f
        val sx = startX + capInset
        val ex = endX - capInset
        if (sx >= ex) {
            // 线太短，退化为一个点/圆
            canvas.drawPoint((startX + endX) / 2f, lineY, paint)
            return
        }
        when (underlineMode) {
            1 -> canvas.drawLine(sx, lineY, ex, lineY, paint)
            2 -> drawDashedLine(canvas, paint, sx, lineY, ex, strokeWidth)
            3 -> drawWavyLine(canvas, paint, sx, lineY, ex, strokeWidth)
            4 -> {
                val line2Y = lineY + doubleLineGap + geomWidth.dpToPx()
                canvas.drawLine(sx, lineY, ex, lineY, paint)
                canvas.drawLine(sx, line2Y, ex, line2Y, paint)
            }
            5 -> {
                if (svgPathStr.isNotBlank()) {
                    drawSvgPath(canvas, startX, endX, lineY, svgPathStr, paint)
                }
            }
        }
    }

    private fun drawDashedLine(canvas: Canvas, paint: Paint, startX: Float, y: Float, endX: Float, underlineWidth: Float) {
        paint.strokeWidth = underlineWidth.dpToPx()
        val dashLen = 8.dpToPx().toFloat()
        val gapLen = 5.dpToPx().toFloat()
        var x = startX
        while (x < endX) {
            val x2 = (x + dashLen).coerceAtMost(endX)
            canvas.drawLine(x, y, x2, y, paint)
            x += dashLen + gapLen
        }
    }

    private fun drawWavyLine(canvas: Canvas, paint: Paint, startX: Float, y: Float, endX: Float, underlineWidth: Float) {
        paint.strokeWidth = underlineWidth.dpToPx()
        val path = Path()
        val waveAmp = waveAmplitude
        val waveLen = waveLength
        path.moveTo(startX, y)
        var currentX = startX
        while (currentX < endX) {
            val nextX = (currentX + waveLen).coerceAtMost(endX)
            val midX = (currentX + nextX) / 2
            path.quadTo(midX, y - waveAmp, nextX, y)
            currentX = nextX
            if (currentX < endX) {
                val nextX2 = (currentX + waveLen).coerceAtMost(endX)
                val midX2 = (currentX + nextX2) / 2
                path.quadTo(midX2, y + waveAmp, nextX2, y)
                currentX = nextX2
            }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawSvgPath(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        lineY: Float,
        svgPathStr: String,
        paint: Paint
    ) {
        val baseWidth = 100f
        val baseY = 50f
        val path = io.legado.app.ui.book.read.config.SvgPathParser.parse(svgPathStr) ?: return

        val width = endX - startX
        val scaleX = width / baseWidth
        val scaleY = 1f
        val translateX = startX
        val translateY = lineY - baseY

        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scaleX, scaleY)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun drawBgImageSegment(
        canvas: Canvas,
        startX: Float,
        endX: Float,
        bgImage: String,
        bgImageFit: Int,
        bgImageScale: Float,
        npLeft: Float = 0.5f,
        npRight: Float = 0.5f,
        npTop: Float = 0.5f,
        npBottom: Float = 0.5f,
        bgPadStart: Float = 0f,
        bgPadEnd: Float = 0f,
        bgPadTop: Float = 0f,
        bgPadBottom: Float = 0f,
    ) {
        val bitmap = getBgBitmap(bgImage) ?: return
        val paint = PaintPool.obtain()
        paint.style = Paint.Style.FILL
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        val top = bgPaddingTop
        val bottom = height - bgPaddingBottom
        val rectWidth = endX - startX
        val rectHeight = bottom - top
        val scale = bgImageScale.coerceIn(0.1f, 5f)
        when (bgImageFit) {
            1 -> {
                val sw = rectWidth * scale
                val sh = rectHeight * scale
                val dx = startX + (rectWidth - sw) / 2f
                val dy = top + (rectHeight - sh) / 2f
                val destination = android.graphics.RectF(dx, dy, dx + sw, dy + sh)
                canvas.save()
                canvas.clipRect(startX, top, endX, bottom)
                if (!drawNinePatchBitmap(canvas, bitmap, destination, paint, bgImage)) {
                    canvas.drawBitmap(bitmap, null, destination, paint)
                }
                canvas.restore()
            }
            2 -> {
                val bw = bitmap.width.toFloat()
                val bh = bitmap.height.toFloat()
                val fitScale = (rectWidth / bw).coerceAtLeast(rectHeight / bh) * scale
                val scaledW = bw * fitScale
                val scaledH = bh * fitScale
                val dx = startX + (rectWidth - scaledW) / 2f
                val dy = top + (rectHeight - scaledH) / 2f
                canvas.save()
                canvas.clipRect(startX, top, endX, bottom)
                canvas.drawBitmap(bitmap, null, android.graphics.RectF(dx, dy, dx + scaledW, dy + scaledH), paint)
                canvas.restore()
            }
            3 -> {
                val padStartPx = bgPadStart.dpToPx()
                val padEndPx = bgPadEnd.dpToPx()
                val padTopPx = bgPadTop.dpToPx()
                val padBottomPx = bgPadBottom.dpToPx()
                // 文字落在九宫格的"中段拉伸区"内，四角自然落在文字外部。
                // 用行高作为基准计算四角像素尺寸，但限制四角不超过行高的一半，
                // 避免线重合时 s 爆炸。NinePatchDrawHelper 内部会借 1px 处理重合。
                val bw = bitmap.width.toFloat()
                val bh = bitmap.height.toFloat()
                val s = if (bh > 0f) height / bh else 1f
                val maxCornerV = height * 0.5f
                val maxCornerH = (endX - startX) * 0.5f
                val cornerL = (npLeft * bw * s).coerceAtMost(maxCornerH)
                val cornerR = (npRight * bw * s).coerceAtMost(maxCornerH)
                val cornerT = (npTop * bh * s).coerceAtMost(maxCornerV)
                val cornerB = (npBottom * bh * s).coerceAtMost(maxCornerV)
                val drawLeft = startX - cornerL - padStartPx
                val drawRight = endX + cornerR + padEndPx
                val drawTop = 0f - cornerT - padTopPx
                val drawBottom = height + cornerB + padBottomPx
                if (drawRight > drawLeft && drawBottom > drawTop) {
                    NinePatchDrawHelper.draw(
                        canvas, bitmap, drawLeft, drawTop, drawRight, drawBottom, paint,
                        npLeft, 1f - npRight, npTop, 1f - npBottom,
                    )
                }
            }
            else -> {
                val tileBitmap = if (scale != 1f) {
                    val sw = (bitmap.width * scale).toInt().coerceAtLeast(1)
                    val sh = (bitmap.height * scale).toInt().coerceAtLeast(1)
                    getScaledBitmap("${bgImage}_s${scale}", bitmap, sw, sh)
                } else {
                    bitmap
                }
                val shader = BitmapShader(tileBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                val matrix = android.graphics.Matrix()
                matrix.setTranslate(startX, top)
                shader.setLocalMatrix(matrix)
                paint.shader = shader
                canvas.drawRect(startX, top, endX, bottom, paint)
                paint.shader = null
            }
        }
        PaintPool.recycle(paint)
    }

    private fun drawNinePatchBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        destination: android.graphics.RectF,
        paint: Paint,
        path: String,
    ): Boolean {
        bitmap.ninePatchChunk?.takeIf(android.graphics.NinePatch::isNinePatchChunk)?.let { chunk ->
            android.graphics.NinePatch(bitmap, chunk, null).draw(
                canvas,
                android.graphics.Rect(
                    destination.left.toInt(),
                    destination.top.toInt(),
                    destination.right.toInt(),
                    destination.bottom.toInt(),
                ),
                paint,
            )
            return true
        }
        if (!isRawNinePatchPath(path) || bitmap.width < 3 || bitmap.height < 3) {
            return false
        }

        val horizontalStretch = findNinePatchStretchRange(bitmap, horizontal = true) ?: return false
        val verticalStretch = findNinePatchStretchRange(bitmap, horizontal = false) ?: return false
        val sourceX = intArrayOf(
            1,
            horizontalStretch.first,
            horizontalStretch.last + 1,
            bitmap.width - 1,
        )
        val sourceY = intArrayOf(
            1,
            verticalStretch.first,
            verticalStretch.last + 1,
            bitmap.height - 1,
        )

        var leftWidth = (sourceX[1] - sourceX[0]).toFloat()
        var rightWidth = (sourceX[3] - sourceX[2]).toFloat()
        val fixedWidth = leftWidth + rightWidth
        if (fixedWidth > destination.width() && fixedWidth > 0f) {
            val ratio = destination.width() / fixedWidth
            leftWidth *= ratio
            rightWidth *= ratio
        }

        var topHeight = (sourceY[1] - sourceY[0]).toFloat()
        var bottomHeight = (sourceY[3] - sourceY[2]).toFloat()
        val fixedHeight = topHeight + bottomHeight
        if (fixedHeight > destination.height() && fixedHeight > 0f) {
            val ratio = destination.height() / fixedHeight
            topHeight *= ratio
            bottomHeight *= ratio
        }

        val destinationX = floatArrayOf(
            destination.left,
            destination.left + leftWidth,
            destination.right - rightWidth,
            destination.right,
        )
        val destinationY = floatArrayOf(
            destination.top,
            destination.top + topHeight,
            destination.bottom - bottomHeight,
            destination.bottom,
        )

        for (row in 0..2) {
            for (column in 0..2) {
                if (sourceX[column] == sourceX[column + 1] ||
                    sourceY[row] == sourceY[row + 1] ||
                    destinationX[column] == destinationX[column + 1] ||
                    destinationY[row] == destinationY[row + 1]
                ) {
                    continue
                }
                canvas.drawBitmap(
                    bitmap,
                    android.graphics.Rect(
                        sourceX[column],
                        sourceY[row],
                        sourceX[column + 1],
                        sourceY[row + 1],
                    ),
                    android.graphics.RectF(
                        destinationX[column],
                        destinationY[row],
                        destinationX[column + 1],
                        destinationY[row + 1],
                    ),
                    paint,
                )
            }
        }
        return true
    }

    private fun findNinePatchStretchRange(bitmap: Bitmap, horizontal: Boolean): IntRange? {
        val limit = if (horizontal) bitmap.width - 2 else bitmap.height - 2
        var first = -1
        var last = -1
        for (position in 1..limit) {
            val color = if (horizontal) bitmap.getPixel(position, 0) else bitmap.getPixel(0, position)
            val isMarker = android.graphics.Color.alpha(color) == 0xFF &&
                android.graphics.Color.red(color) == 0 &&
                android.graphics.Color.green(color) == 0 &&
                android.graphics.Color.blue(color) == 0
            if (isMarker) {
                if (first < 0) first = position
                last = position
            }
        }
        return if (first >= 0) first..last else null
    }

    fun checkFastDraw(): Boolean {
        if (!ChapterProvider.renderStyle.optimizeRender || exceed || !onlyTextColumn || textPage.isMsgPage) {
            return false
        }
        if (wordSpacing != 0f && (!atLeastApi26 || !wordSpacingWorking)) {
            return false
        }
        return searchResultColumnCount == 0
    }

    fun invalidate() {
        invalidateSelf()
        textPage.invalidate()
    }

    fun invalidateSelf() {
        canvasRecorder.invalidate()
    }

    fun recycleRecorder() {
        canvasRecorder.recycle()
    }

    @SuppressLint("NewApi")
    companion object {
        val emptyTextLine = TextLine()
        private val atLeastApi26 = true
        private val atLeastApi35 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        private val bgPaddingTop = 1.dpToPx().toFloat()
        private val bgPaddingBottom = 1.dpToPx().toFloat()
        private val waveAmplitude = 3.dpToPx().toFloat()
        private val waveLength = 12.dpToPx().toFloat()
        private val doubleLineGap = 3.dpToPx().toFloat()
        private val bgBitmapCache = android.util.LruCache<String, Bitmap>(16 * 1024 * 1024)
        private val failedBgBitmapLoads = ResourceLoadFailureCache<String>()
        private const val MAX_CORNER_PX = 256

        /**
         * Trims bitmap caches to free memory under pressure.
         * Call from [android.app.Application.onTrimMemory].
         */
        fun trimCaches(level: Int) {
            when {
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                    bgBitmapCache.evictAll()
                }
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                    bgBitmapCache.trimToSize(4 * 1024 * 1024)
                }
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                    bgBitmapCache.trimToSize(8 * 1024 * 1024)
                }
            }
        }

        private val bgSampleWidth by lazy {
            appCtx.resources.displayMetrics.widthPixels
        }
        private val bgSampleHeight by lazy {
            appCtx.resources.displayMetrics.heightPixels
        }

        private val wordSpacingWorking by lazy {
            val paint = PaintPool.obtain()
            val text = "一二 三"
            val width1 = paint.measureText(text)
            try {
                paint.wordSpacing = 10f
                val width2 = paint.measureText(text)
                width2 - width1 == 10f
            } catch (e: NoSuchMethodError) {
                false
            } finally {
                PaintPool.recycle(paint)
            }
        }

        fun getBgBitmap(path: String): Bitmap? {
            if (path.isBlank()) return null
            bgBitmapCache.get(path)?.let { return it }
            return failedBgBitmapLoads.load(path) {
                loadBgBitmap(path)?.also { bitmap ->
                    bgBitmapCache.put(path, bitmap)
                }
            }
        }

        private fun getScaledBitmap(path: String, source: Bitmap, width: Int, height: Int): Bitmap {
            if (width <= 0 || height <= 0) return source
            return Bitmap.createScaledBitmap(source, width, height, true)
        }

        private fun loadBgBitmap(path: String): Bitmap? {
            return try {
                val ctx = appCtx
                val sampleBitmap = !isRawNinePatchPath(path)
                if (path.startsWith("assets://")) {
                    val assetPath = path.removePrefix("assets://")
                    ctx.assets.open(assetPath).use { input ->
                        decodeSampledBitmap(input, sampleBitmap)
                    }
                } else if (path.startsWith("content://")) {
                    val uri = android.net.Uri.parse(path)
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        decodeSampledBitmap(input, sampleBitmap)
                    }
                } else {
                    val file = java.io.File(path)
                    if (file.exists()) {
                        decodeSampledBitmapFile(path, sampleBitmap)
                    } else {
                        val assetPath = if (path.startsWith("bg/")) path else "bg/$path"
                        runCatching {
                            ctx.assets.open(assetPath).use { input ->
                                decodeSampledBitmap(input, sampleBitmap)
                            }
                        }.getOrNull()
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        private fun decodeSampledBitmap(
            input: java.io.InputStream,
            sampleBitmap: Boolean,
        ): Bitmap? {
            val buffered = if (input.markSupported()) input else java.io.BufferedInputStream(input)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            buffered.mark(buffered.available())
            BitmapFactory.decodeStream(buffered, null, options)
            options.inSampleSize = if (sampleBitmap) {
                calculateInSampleSize(options, bgSampleWidth, bgSampleHeight)
            } else {
                1
            }
            options.inJustDecodeBounds = false
            buffered.reset()
            return BitmapFactory.decodeStream(buffered, null, options)
        }

        private fun decodeSampledBitmapFile(path: String, sampleBitmap: Boolean): Bitmap? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)
            options.inSampleSize = if (sampleBitmap) {
                calculateInSampleSize(options, bgSampleWidth, bgSampleHeight)
            } else {
                1
            }
            options.inJustDecodeBounds = false
            return BitmapFactory.decodeFile(path, options)
        }

        private fun isRawNinePatchPath(path: String): Boolean {
            return path.substringBefore('?').substringBefore('#').endsWith(".9.png", ignoreCase = true)
        }

        private fun calculateInSampleSize(
            options: BitmapFactory.Options,
            reqWidth: Int,
            reqHeight: Int
        ): Int {
            val (height, width) = options.outHeight to options.outWidth
            var inSampleSize = 1
            if (height > reqHeight || width > reqWidth) {
                val halfHeight = height / 2
                val halfWidth = width / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }
            return inSampleSize
        }

        fun cleanupUnusedBgImages(context: android.content.Context, usedPaths: Set<String>) {
            val dir = java.io.File(context.filesDir, "bg_images")
            if (!dir.exists()) return
            dir.listFiles()?.forEach { file ->
                if (file.absolutePath !in usedPaths) {
                    runCatching { file.delete() }
                }
            }
        }

        fun copyBgImageToInternal(context: android.content.Context, sourcePath: String): String? {
            return runCatching {
                val sourceFile = java.io.File(sourcePath)
                if (!sourceFile.exists() || !sourceFile.isFile) return@runCatching null
                val dir = java.io.File(context.filesDir, "bg_images")
                if (!dir.exists()) dir.mkdirs()
                val targetFile = java.io.File(dir, sourceFile.name)
                if (!targetFile.exists() || targetFile.length() != sourceFile.length()) {
                    sourceFile.copyTo(targetFile, overwrite = true)
                }
                targetFile.absolutePath
            }.getOrNull()
        }
    }

}
