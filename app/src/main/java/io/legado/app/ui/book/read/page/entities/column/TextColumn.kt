package io.legado.app.ui.book.read.page.entities.column

import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.Keep
import androidx.core.net.toUri
import io.legado.app.ui.book.read.page.ContentTextView
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.emptyTextLine
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.book.read.page.ResourceLoadFailureCache
import io.legado.app.utils.isContentScheme
import splitties.init.appCtx
import java.io.File

@Keep
data class TextColumn(
    override var start: Float,
    override var end: Float,
    override val charData: String,
    override val textColor: Int? = null,
    override val bgColor: Int? = null,
    override val underlineMode: Int = 0,
    override val underlineColor: Int? = null,
    override val underlineWidth: Float = 1f,
    override val underlineOffset: Float = 2f,
    override val underlineSvgPath: String = "",
    override val bgImage: String = "",
    override val bgImageFit: Int = 0,
    override val bgImageScale: Float = 1f,
    override val fontPath: String = "",
    override val fontWeight: Int = 400,
    override val isItalic: Boolean = false,
    override val npLeft: Float = 0.5f,
    override val npRight: Float = 0.5f,
    override val npTop: Float = 0.5f,
    override val npBottom: Float = 0.5f,
    override val bgPadStart: Float = 0f,
    override val bgPadEnd: Float = 0f,
    override val bgPadTop: Float = 0f,
    override val bgPadBottom: Float = 0f,
    override val bgMarginStart: Float = 0f,
    override val bgMarginEnd: Float = 0f,
    override val bgMarginTop: Float = 0f,
    override val bgMarginBottom: Float = 0f,
) : TextBaseColumn {

    override var textLine: TextLine = emptyTextLine

    override var selected: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
            }
            field = value
        }
    override var isSearchResult: Boolean = false
        set(value) {
            if (field != value) {
                textLine.invalidate()
                if (value) {
                    textLine.searchResultColumnCount++
                } else {
                    textLine.searchResultColumnCount--
                }
            }
            field = value
        }

    override fun draw(view: ContentTextView, canvas: Canvas) {
        val textPaint = if (textLine.isTitle) {
            ChapterProvider.titlePaint
        } else {
            ChapterProvider.contentPaint
        }
        val renderStyle = ChapterProvider.renderStyle
        val drawColor = if (textLine.isReadAloud || isSearchResult) {
            renderStyle.textAccentColor
        } else {
            textColor ?: if (textLine.isTitle && renderStyle.titleColor != 0) {
                renderStyle.titleColor
            } else {
                renderStyle.textColor
            }
        }
        val titleTextSize = textLine.titleTextSize
        val needRestoreColor = textPaint.color != drawColor
        val customTypeface = getCustomTypeface()
        val needRestoreTypeface = customTypeface != null
        // 合成加粗：字重 >= 700 时用 isFakeBoldText 保证任意字体都能变粗
        val needFakeBold = fontWeight >= 700
        if (titleTextSize == null && !needRestoreColor && !needRestoreTypeface && !needFakeBold) {
            drawText(canvas, textLine.lineBase - textLine.lineTop, textPaint)
        } else {
            val originalSize = textPaint.textSize
            val originalColor = textPaint.color
            val originalTypeface = textPaint.typeface
            val originalFakeBold = textPaint.isFakeBoldText
            try {
                if (titleTextSize != null) textPaint.textSize = titleTextSize
                if (needRestoreColor) textPaint.color = drawColor
                if (needRestoreTypeface) textPaint.typeface = customTypeface
                if (needFakeBold) textPaint.isFakeBoldText = true
                drawText(canvas, textLine.lineBase - textLine.lineTop, textPaint)
            } finally {
                // 共享 paint，任何路径都必须复原，否则样式会泄漏到后续文字
                if (titleTextSize != null) textPaint.textSize = originalSize
                if (needRestoreColor) textPaint.color = originalColor
                if (needRestoreTypeface) textPaint.typeface = originalTypeface
                if (needFakeBold) textPaint.isFakeBoldText = originalFakeBold
            }
        }
        if (selected) {
            canvas.drawRect(start, 0f, end, textLine.height, view.selectedPaint)
        }
    }

    private fun drawText(canvas: Canvas, y: Float, textPaint: android.text.TextPaint) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            val letterSpacing = textPaint.letterSpacing * textPaint.textSize
            val letterSpacingHalf = letterSpacing * 0.5f
            canvas.drawText(charData, start + letterSpacingHalf, y, textPaint)
        } else {
            canvas.drawText(charData, start, y, textPaint)
        }
    }

    private fun getCustomTypeface(): Typeface? {
        return getTypeface(fontPath, fontWeight, isItalic)
    }

    companion object {
        private val typefaceCache = HashMap<String, Typeface>()
        private val failedTypefaceLoads = ResourceLoadFailureCache<String>()

        internal fun getTypeface(fontPath: String, fontWeight: Int = 400, isItalic: Boolean = false): Typeface? {
            if (fontPath.isEmpty()) {
                return applyStyleTypeface(null, fontWeight, isItalic)
            }
            typefaceCache[fontPath]?.let { return applyStyleTypeface(it, fontWeight, isItalic) }
            return failedTypefaceLoads.load(fontPath) {
                loadTypeface(fontPath)?.also { typeface ->
                    typefaceCache[fontPath] = typeface
                }
            }?.let { applyStyleTypeface(it, fontWeight, isItalic) }
        }

        private fun applyStyleTypeface(typeface: Typeface?, fontWeight: Int, isItalic: Boolean): Typeface? {
            if (fontWeight == 400 && !isItalic) return typeface
            // 无自定义字体时不改动 typeface，加粗完全交给 isFakeBoldText 承担，
            // 避免把用户阅读字体切换成 Typeface.DEFAULT 造成的整体观感异常。
            val base = typeface ?: return null
            val style = when {
                isItalic && fontWeight == 700 -> Typeface.BOLD_ITALIC
                isItalic -> Typeface.ITALIC
                fontWeight == 700 -> Typeface.BOLD
                else -> Typeface.NORMAL // 300 (Light) falls back to NORMAL via Typeface.create
            }
            return Typeface.create(base, style)
        }

        private fun loadTypeface(fontPath: String): Typeface? {
            return runCatching {
                when {
                    fontPath.isContentScheme() -> {
                        appCtx.contentResolver
                            .openFileDescriptor(fontPath.toUri(), "r")!!
                            .use { Typeface.Builder(it.fileDescriptor).build() }
                    }
                    fontPath.isNotEmpty() -> {
                        Typeface.Builder(File(fontPath)).build()
                    }
                    else -> null
                }
            }.getOrNull()
        }
    }
}
