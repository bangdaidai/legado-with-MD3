package io.legado.app.feature.reader.core.model

data class ReaderPageId(val chapterIndex: Int, val pageIndex: Int)

data class ReaderRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
    fun offsetY(deltaY: Float) = copy(top = top + deltaY, bottom = bottom + deltaY)
}

data class ReaderTextStyle(
    val colorArgb: Int,
    val fontSizePx: Float,
    val fontPath: String = "",
    val fontWeight: Int = 400,
    val italic: Boolean = false,
    val backgroundArgb: Int? = null,
    val underline: ReaderUnderline? = null,
    val shadow: ReaderTextShadow? = null,
    val backgroundImage: ReaderTextBackgroundImage? = null,
    val fontFamily: String = "sans-serif",
    val linearText: Boolean = false,
    val strikeThrough: Boolean = false,
    /** Font-native underline used by HTML UnderlineSpan; custom reader underlines stay separate. */
    val nativeUnderline: Boolean = false,
)

data class ReaderTextBackgroundImage(
    val source: String,
    val fit: Int,
    val scale: Float,
    val ninePatchLeft: Float = 0.1f,
    val ninePatchRight: Float = 0.1f,
    val ninePatchTop: Float = 0.1f,
    val ninePatchBottom: Float = 0.1f,
    val contentInsetLeftPx: Float = 0f,
    val contentInsetRightPx: Float = 0f,
    val contentInsetTopPx: Float = 0f,
    val contentInsetBottomPx: Float = 0f,
    /** 九宫格外扩（px）。旧引擎 bgPadding*：背景图相对文字向外多画一圈，随 inset 一起参与排版避让。 */
    val paddingLeftPx: Float = 0f,
    val paddingRightPx: Float = 0f,
    val paddingTopPx: Float = 0f,
    val paddingBottomPx: Float = 0f,
    /** 背景段与相邻文字的水平间距（px），只在排版避让时额外让出，不随图片画出。旧引擎 bgMargin*Start/End。 */
    val marginStartPx: Float = 0f,
    val marginEndPx: Float = 0f,
    /** 剥离 .9.png 引导边后的源图尺寸（px），0=未知。九宫格按行高锚定四角时需要。 */
    val sourceWidthPx: Int = 0,
    val sourceHeightPx: Int = 0,
) {
    val hasNinePatchBorder: Boolean
        get() = source.substringBefore('?').substringBefore('#')
            .endsWith(".9.png", ignoreCase = true)
}

fun ReaderTextBackgroundImage.withBitmapWidth(widthPx: Int): ReaderTextBackgroundImage {
    return withBitmapSize(widthPx, 0)
}

fun ReaderTextBackgroundImage.withBitmapSize(widthPx: Int, heightPx: Int): ReaderTextBackgroundImage {
    if (fit != 3 || widthPx <= 0) return this
    val borderPx = if (hasNinePatchBorder) 1 else 0
    val contentWidthPx = (widthPx - borderPx * 2).coerceAtLeast(0)
    val contentHeightPx = (heightPx - borderPx * 2).coerceAtLeast(0)
    val fixedScale = scale.coerceIn(0.1f, 5f)
    return copy(
        contentInsetLeftPx = contentWidthPx * ninePatchLeft.coerceIn(0f, 1f) * fixedScale +
            paddingLeftPx.coerceAtLeast(0f),
        contentInsetRightPx = contentWidthPx * ninePatchRight.coerceIn(0f, 1f) * fixedScale +
            paddingRightPx.coerceAtLeast(0f),
        contentInsetTopPx = contentHeightPx * ninePatchTop.coerceIn(0f, 1f) * fixedScale +
            paddingTopPx.coerceAtLeast(0f),
        contentInsetBottomPx = contentHeightPx * ninePatchBottom.coerceIn(0f, 1f) * fixedScale +
            paddingBottomPx.coerceAtLeast(0f),
        sourceWidthPx = contentWidthPx,
        sourceHeightPx = contentHeightPx,
    )
}

/**
 * 九宫格四角按行高等比锚定：中带源高缩放后恰好铺满 [lineHeightPx]，四角随同一 scale
 * 同步放大缩小——与 [io.legado.app.help.highlight.NinePatchDrawHelper.layout]（编辑规则
 * 预览所用）同一口径，正文小图案不再与预览不一致。bgImageScale 作为角块相对中带占
 * 比的整体微调（旧引擎无此维度，默认 1 即与预览完全一致）。
 */
fun ReaderTextBackgroundImage.anchoredToLineHeight(lineHeightPx: Float): ReaderTextBackgroundImage {
    if (fit != 3 || sourceHeightPx <= 0 || lineHeightPx <= 0f) return this
    val middleSrcH = (1f - ninePatchTop.coerceIn(0f, 1f) - ninePatchBottom.coerceIn(0f, 1f))
        .coerceIn(0.02f, 1f) * sourceHeightPx
    val scale = (lineHeightPx / middleSrcH) * this.scale.coerceIn(0.1f, 5f)
    return copy(
        contentInsetLeftPx = sourceWidthPx * ninePatchLeft.coerceIn(0f, 1f) * scale +
            paddingLeftPx.coerceAtLeast(0f),
        contentInsetRightPx = sourceWidthPx * ninePatchRight.coerceIn(0f, 1f) * scale +
            paddingRightPx.coerceAtLeast(0f),
        contentInsetTopPx = sourceHeightPx * ninePatchTop.coerceIn(0f, 1f) * scale +
            paddingTopPx.coerceAtLeast(0f),
        contentInsetBottomPx = sourceHeightPx * ninePatchBottom.coerceIn(0f, 1f) * scale +
            paddingBottomPx.coerceAtLeast(0f),
    )
}

data class ReaderTextShadow(
    val colorArgb: Int,
    val radiusPx: Float,
    val dxPx: Float,
    val dyPx: Float,
)

data class ReaderUnderline(
    val mode: Int,
    val colorArgb: Int,
    val widthPx: Float,
    val offsetPx: Float,
    val svgPath: String = "",
    val dashOnPx: Float = 8f,
    val dashOffPx: Float = 5f,
    val waveAmplitudePx: Float = 3f,
    val waveLengthPx: Float = 12f,
    val doubleLineGapPx: Float = 3f,
    /** 端点圆头（旧 underlineRoundCap）；羽化 >0 时同样强制圆头，与旧绘制一致。 */
    val roundCap: Boolean = false,
    /** 羽化半径（px），0=不羽化（旧 underlineFeather 多趟高斯 alpha + 端点渐隐）。 */
    val featherPx: Float = 0f,
    /** true=画在文字层之下（旧 underlineBelowText，被字形笔画遮挡）。 */
    val belowText: Boolean = false,
)

sealed interface ReaderElement {
    val bounds: ReaderRect

    data class Text(
        override val bounds: ReaderRect,
        val baselinePx: Float,
        val value: String,
        val style: ReaderTextStyle,
        val selected: Boolean,
        val emphasized: Boolean,
        val readAloud: Boolean = false,
        val searchResult: Boolean = false,
        val emphasisUnderline: ReaderEmphasisUnderline? = null,
        val link: String? = null,
        val markingId: String? = null,
        val chapterPosition: Int,
        val paragraphIndex: Int = -1,
        val backgroundFrameTopPx: Float = 0f,
        val backgroundFrameBottomPx: Float = 0f,
        /** 同一行内紧随同背景图元素之后（对照旧 View TextLine 的行内连续绘制）。 */
        val continuesBackgroundRun: Boolean = false,
    ) : ReaderElement {
        /** HTML links keep the legacy reader's accent priority, including during read-aloud. */
        fun resolvedColorArgb(accentColorArgb: Int): Int =
            if (link != null || readAloud || searchResult) accentColorArgb else style.colorArgb

        val drawsLinkUnderline: Boolean
            get() = link != null
    }

    data class Image(
        override val bounds: ReaderRect,
        val source: String,
        val action: String?,
        val chapterPosition: Int = 0,
    ) : ReaderElement

    data class Review(
        override val bounds: ReaderRect,
        val count: Int,
        val paragraphIndex: Int,
        val baselinePx: Float = bounds.bottom,
        val textSizePx: Float = bounds.height,
    ) : ReaderElement

    data class Action(
        override val bounds: ReaderRect,
        val key: String,
    ) : ReaderElement

    data class Spacer(
        override val bounds: ReaderRect,
        val chapterPosition: Int,
        val paragraphIndex: Int,
    ) : ReaderElement

    data class ParagraphMarker(
        override val bounds: ReaderRect,
        val colorArgb: Int,
        val strokeWidthPx: Float,
        val circular: Boolean,
    ) : ReaderElement

    data class Rule(
        override val bounds: ReaderRect,
        val colorArgb: Int,
        val widthPx: Float,
        val dashed: Boolean,
        val dashOnPx: Float = 6f,
        val dashOffPx: Float = 6f,
        val overlayStyledUnderline: Boolean = false,
    ) : ReaderElement
}

data class ReaderPage(
    val id: ReaderPageId,
    val chapterTitle: String,
    val text: String,
    val widthPx: Int,
    val heightPx: Int,
    val contentTopPx: Float,
    val contentBottomPx: Float,
    val elements: List<ReaderElement>,
    val revision: Long,
    /** Changes only when geometry/pagination changes; visual-only refreshes keep this stable. */
    val layoutRevision: Long = revision,
    val scrollExtentPx: Float = heightPx.toFloat(),
    val decoration: ReaderPageDecoration = ReaderPageDecoration(),
    val inlineImagesPreserveScrollLine: Boolean = true,
    val emphasisUnderlineStyle: ReaderEmphasisUnderline? = null,
    /** Dynamic search range, kept separate from immutable layout elements for draw-cache reuse. */
    val searchStart: Int? = null,
    val searchEndInclusive: Int? = null,
    /** Whether the dynamic search range is in the independent title coordinate space. */
    val searchIsTitle: Boolean = false,
    /** Dynamic read-aloud paragraph, likewise independent of the pagination layout. */
    val readAloudParagraphIndex: Int? = null,
    /** 邻章未装载时预置的"加载中"占位页，分页批次落地后被同 id 真实页替换。 */
    val isPlaceholder: Boolean = false,
    /**
     * 内容区左右边界，对照旧 `ChapterProvider.visibleRect` 的左右边
     * （`paddingLeft` / `viewWidth - paddingRight`）。放在构造参数末尾是为了不破坏按位置
     * 构造 `ReaderPage` 的既有调用点；默认值等价于"整页宽"，即不额外裁剪。
     */
    val contentLeftPx: Float = 0f,
    val contentRightPx: Float = widthPx.toFloat(),
) {
    fun elementAt(x: Float, y: Float): ReaderElement? =
        elements.firstOrNull { it.bounds.contains(x, y) }

    fun hasSameGeometryAs(other: ReaderPage): Boolean =
        id == other.id &&
            widthPx == other.widthPx && heightPx == other.heightPx &&
            contentTopPx == other.contentTopPx && contentBottomPx == other.contentBottomPx &&
            scrollExtentPx == other.scrollExtentPx &&
            elements.size == other.elements.size &&
            elements.indices.all { index ->
                elements[index]::class == other.elements[index]::class &&
                    elements[index].bounds == other.elements[index].bounds
            }
}

data class ReaderPageWindow(
    val previous: ReaderPage? = null,
    val current: ReaderPage? = null,
    val next: ReaderPage? = null,
    /** 下下页：滚动视口可露出它；分页模式仅将其作为预热页（对照 shutiao 的四页流）。 */
    val nextPlus: ReaderPage? = null,
)

enum class ReaderTipAlignment { START, CENTER, END }

enum class ReaderTipVisual { TEXT, BATTERY_OUTER, BATTERY_INNER, BATTERY_ICON, BATTERY_CLASSIC, ARROW }

data class ReaderPageTip(
    val text: String,
    val alignment: ReaderTipAlignment,
    val visual: ReaderTipVisual = ReaderTipVisual.TEXT,
    val batteryPercent: Int = 0,
)

data class ReaderTipRow(
    val visible: Boolean,
    val tips: List<ReaderPageTip>,
    val colorArgb: Int,
    val fontSizePx: Float,
    val fontPath: String,
    val paddingLeftPx: Float,
    val paddingTopPx: Float,
    val paddingRightPx: Float,
    val paddingBottomPx: Float,
    val dividerColorArgb: Int?,
    /** 未设页眉页脚字体时回落正文字体族（旧 `tipTypeface ?: ChapterProvider.typeface`）。 */
    val fontFamily: String = "sans-serif",
    /** 根层安全区内缩：分隔线只画在内缩后的宽度里（旧 `vwRoot` 的刘海 padding）。 */
    val insetLeftPx: Float = 0f,
    val insetRightPx: Float = 0f,
)

data class ReaderPageDecoration(
    val header: ReaderTipRow? = null,
    val footer: ReaderTipRow? = null,
    val bookmarkBadge: ReaderBookmarkBadge? = null,
)
