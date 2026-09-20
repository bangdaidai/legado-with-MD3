package io.legado.app.feature.reader.platform

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import androidx.core.net.toUri
import io.legado.app.feature.reader.core.layout.GlyphClusters
import io.legado.app.feature.reader.core.layout.ReaderFontBounds
import io.legado.app.feature.reader.core.layout.ReaderFontLineMetrics
import io.legado.app.feature.reader.core.layout.ReaderTextShaper
import io.legado.app.feature.reader.core.layout.clusterGlyphs
import io.legado.app.feature.reader.core.model.ReaderTextStyle
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.utils.validFontLeading
import splitties.init.appCtx
import java.io.File

object ReaderAndroidPaintFactory {
    /** 进程级字体缓存：同一 path/weight/italic/family 只做一次磁盘读取与解析。 */
    private val typefaceCache = java.util.concurrent.ConcurrentHashMap<String, Typeface>()

    fun create(style: ReaderTextStyle): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = style.colorArgb
            textSize = style.fontSizePx
            // 规则里的"粗体"存 700：旧口径直接抬到 900（中文静态字体的最黑档），
            // 相对正文过重。改为按**实际正文字重的 2 倍**取粗，封顶 900、保底 700，
            // 让加粗强度随正文粗细联动，而不是无脑拉满。
            val weight = style.fontWeight.let { if (it == 700) ruleBoldWeight() else it }
            typeface = loadTypeface(style.fontPath, weight, false, style.fontFamily)
            // Match the reader's synthetic italic; don't substitute another font's italic face.
            textSkewX = if (style.italic) -0.25f else 0f
            isLinearText = style.linearText
            isStrikeThruText = style.strikeThrough
            isUnderlineText = style.nativeUnderline
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 对照旧 View `ChapterProvider`：只有显式字重（100..900，映射后落在
                // bold/light 档位）才写 `wght`；`400` 在本仓库语义是"未设置/常规"
                // （见 LegacyReaderStyleRangeMapper 的 400 约定），旧版此时完全不动
                // Paint，强写 `wght 400` 会把字体自带的默认实例（如 500）改细。
                weight.takeIf { it != 400 }
                    ?.let { setFontVariationSettings("'wght' $it") }
            }
            style.shadow?.let { setShadowLayer(it.radiusPx, it.dxPx, it.dyPx, it.colorArgb) }
        }

    /**
     * 规则加粗字重：以正文实际字重（`ReadBookConfig.textBold` 归一后的值）的 2 倍为准，
     * 封顶 900、保底 700（保证仍是可辨的粗体）。正文越粗，规则加粗越接近最黑档。
     */
    private fun ruleBoldWeight(): Int {
        val bodyWeight = when (val bold = ReadBookConfig.textBold) {
            1 -> 900
            2 -> 300
            in 100..900 -> bold
            else -> 400
        }
        return (bodyWeight * 2).coerceIn(700, 900)
    }

    fun createTextPaint(style: ReaderTextStyle): TextPaint = TextPaint(create(style))

    /** 行盒基线偏移：行高已排除异常 leading（见 PaintExtensions.validFontLeading）。 */
    fun baselineOffset(paint: Paint): Float = paint.fontMetrics.let { paint.validFontLeading - it.ascent }

    fun loadTypeface(path: String, weight: Int, italic: Boolean, family: String = "sans-serif"): Typeface {
        val key = "$path|$weight|$italic|$family"
        typefaceCache[key]?.let { return it }
        val base = runCatching {
            when {
                path.startsWith("content://", ignoreCase = true) ->
                    appCtx.contentResolver.openFileDescriptor(path.toUri(), "r")?.use {
                        Typeface.Builder(it.fileDescriptor).build()
                    }
                path.isNotBlank() && File(path).isFile -> Typeface.Builder(File(path)).build()
                else -> null
            }
        }.getOrNull() ?: Typeface.create(family, Typeface.NORMAL)
        val typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Typeface.create(base, weight.coerceIn(1, 1000), italic)
        } else {
            val typefaceStyle = when {
                weight >= 600 && italic -> Typeface.BOLD_ITALIC
                weight >= 600 -> Typeface.BOLD
                italic -> Typeface.ITALIC
                else -> Typeface.NORMAL
            }
            Typeface.create(base, typefaceStyle)
        }
        typefaceCache[key] = typeface
        return typeface
    }
}

/** Android shaping boundary. The paint must match the paint used by ReaderCanvasSurface. */
class AndroidReaderTextShaper(paint: TextPaint) : ReaderTextShaper {
    // Pagination inserts the gap between clusters. Do not include it again in glyph widths.
    private val paint = TextPaint(paint).apply { letterSpacing = 0f }
    override val fontBounds = this.paint.fontMetrics.let { ReaderFontBounds(it.top, it.bottom, it.descent) }
    override val fontLineMetrics = this.paint.fontMetrics.let {
        // 行盒高度与 utils/PaintExtensions.textHeight 同规则：异常 leading 不计入
        //（如方正新楷体声明 leading≈1em，原样计入会让行高翻倍、空隙全在字形上方）
        val height = it.descent - it.ascent + this.paint.validFontLeading
        ReaderFontLineMetrics(
            heightPx = height,
            baselineOffsetPx = height - it.descent,
            ascentPx = -it.ascent,
            descentPx = it.descent,
        )
    }

    override fun shape(text: String): GlyphClusters {
        if (text.isEmpty()) return GlyphClusters(emptyList(), emptyList())
        val widths = FloatArray(text.length)
        paint.getTextWidths(text, widths)
        return clusterGlyphs(text, widths)
    }
}
