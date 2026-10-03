package io.legado.app.feature.reader.core.model

import io.legado.app.feature.reader.core.style.underlineControlSupport

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
    /** 九宫格外扩（dp→px）。旧引擎 bgPadding*：与规则编辑预览同一套语义，
     * 上下 padding 并入中段拉伸区，不塞进角块。 */
    val paddingLeftPx: Float = 0f,
    val paddingRightPx: Float = 0f,
    val paddingTopPx: Float = 0f,
    val paddingBottomPx: Float = 0f,
    /** 背景段与相邻文字的水平间距（px），只在排版避让时额外让出，不随图片画出。旧引擎 bgMargin*Start/End。 */
    val marginStartPx: Float = 0f,
    val marginEndPx: Float = 0f,
    /** 剥离 .9.png 引导边后的源图尺寸（px），0=未知。 */
    val sourceWidthPx: Int = 0,
    val sourceHeightPx: Int = 0,
) {
    val hasNinePatchBorder: Boolean
        get() = source.substringBefore('?').substringBefore('#')
            .endsWith(".9.png", ignoreCase = true)
}

fun ReaderTextBackgroundImage.withBitmapSize(widthPx: Int, heightPx: Int): ReaderTextBackgroundImage {
    if (fit != 3 || widthPx <= 0) return this
    val borderPx = if (hasNinePatchBorder) 1 else 0
    return copy(
        sourceWidthPx = (widthPx - borderPx * 2).coerceAtLeast(0),
        sourceHeightPx = (heightPx - borderPx * 2).coerceAtLeast(0),
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
    /** 波浪控制点竖直偏移。注意实际峰高是它的一半（quad 中点只到控制点的 50%）。 */
    val waveControlOffsetPx: Float = 3f,
    /** 波浪半波长：一次 `quadTo` 覆盖的宽度，整波长是它的两倍。 */
    val waveHalfWavePx: Float = 12f,
    val doubleLineGapPx: Float = 3f,
    /** 端点圆头（旧 underlineRoundCap）；羽化 >0 时同样强制圆头，与旧绘制一致。 */
    val roundCap: Boolean = false,
    /** 羽化半径（px），0=不羽化（旧 underlineFeather 多趟高斯 alpha + 端点渐隐）。 */
    val featherPx: Float = 0f,
    /** true=画在文字层之下（旧 underlineBelowText，被字形笔画遮挡）。 */
    val belowText: Boolean = false,
) {
    /**
     * 圆头与羽化按线型适用性取。
     *
     * 双实线的两条线各自成段（圆头会吃掉两线间距）、删除线固定在行高比例处、
     * 自定义 SVG 的两端由用户路径决定——这三个线型都不开放这两项，编辑弹层已隐藏
     * 对应控件，旧数据里存的值也不该继续生效。渲染层与 UI 必须读同一份判定。
     */
    val roundCapEffective: Boolean
        get() = underlineControlSupport(mode).roundCap && roundCap

    val featherEffective: Boolean
        get() = underlineControlSupport(mode).feather && featherPx > 0f

    /** 圆头补偿只按线芯宽度；羽化加粗后的 pass 不得加大内缩，否则外圈圆头被吃掉。 */
    val capInsetPx: Float
        get() = if (roundCapEffective || featherEffective) widthPx.coerceAtLeast(1f) / 2f else 0f

    /**
     * 下划线画出文字包围盒外的最大半径（圆头/羽化/偏移/双线/波浪），
     * 供内容裁剪与预览画布预留，避免贴边把圆头和柔边切掉。
     */
    val overflowPadPx: Float
        get() {
            val half = widthPx.coerceAtLeast(1f) / 2f
            val feather = if (featherEffective) featherPx.coerceAtLeast(0f) else 0f
            val horizontal = if (roundCapEffective || feather > 0f) half + feather else 0f
            val below = half + feather + offsetPx.coerceAtLeast(0f) + when (mode) {
                // 波浪的 waveControlOffsetPx 是二次贝塞尔的控制点偏移，中点只到它的一半，
                // 真正画出到基线外的距离只有一半；这里按实际峰高留白，和
                // ReaderUnderlineGeometry 的几何口径一致。
                3 -> waveControlOffsetPx.coerceAtLeast(0f) / 2f
                // 双实线第二条在下方：净间隙 + 线宽
                4 -> doubleLineGapPx.coerceAtLeast(0f) + widthPx.coerceAtLeast(1f)
                else -> 0f
            }
            val above = half + feather + (-offsetPx).coerceAtLeast(0f)
            return maxOf(horizontal, below, above)
        }
}

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
        /** 同一行内紧随同背景图元素之后（对照旧 View TextLine 的行内连续绘制）。 */
        val continuesBackgroundRun: Boolean = false,
        /** 文字色由规则或笔记区间覆盖而来，不能当作笔记预览的正文基准色。 */
        val colorFromStyleRange: Boolean = false,
        /** 段首空白：排版期就不吃高亮/笔记装饰（旧 `clearLeadingWhitespaceStyles`）。 */
        val decorationExempt: Boolean = false,
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
        /**
         * 文字嵌入（行内图），对照旧 View `TextChapterLayout` 的 `ImageColumn`：**宽恒为一个
         * 字符格**，高按实际加载到的位图长宽比换算，竖直居中于行盒且允许高于当前行。
         *
         * [bounds] 里的高是测量期由 `imageDimensionsResolver` 给出的长宽比，只用于行盒预留；
         * 绘制期必须按位图重算（见 `ReaderImageDrawLayout.forElement`），否则测量期长宽比与
         * 位图不一致时 `fitCenter` 会在格内留白、把图片画小。
         */
        val inline: Boolean = false,
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
