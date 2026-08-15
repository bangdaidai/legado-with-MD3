package io.legado.app.domain.model

import androidx.annotation.Keep

@Keep
data class TextProcessAnchor(
    val chapterIndex: Int,
    val chapterPosition: Int? = null,
    val selectedText: String,
    val contextBefore: String = "",
    val contextAfter: String = "",
    val normalizedTextHash: String,
)

@Keep
data class TextProcessAction(
    val type: String,
    val replacement: String? = null,
    val text: String? = null,
) {
    companion object {
        const val TYPE_REPLACE = "replace"
        const val TYPE_DELETE = "delete"
        const val TYPE_INSERT_BEFORE = "insert_before"
        const val TYPE_INSERT_AFTER = "insert_after"

        /** 用户划线/高亮标记：不改文本，样式由 styleJson 承载，文本仅供锚点定位。 */
        const val TYPE_MARK = "mark"

        fun replace(replacement: String): TextProcessAction {
            return TextProcessAction(TYPE_REPLACE, replacement = replacement)
        }

        fun delete(): TextProcessAction {
            return TextProcessAction(TYPE_DELETE)
        }
    }
}

@Keep
data class TextProcessStyle(
    val textColor: Int? = null,
    val bgColor: Int? = null,
    val underlineMode: Int = 0,
    val underlineColor: Int? = null,
    val underlineWidth: Float = 1f,
    val underlineOffset: Float = 2f,
    val underlineSvgPath: String? = null,
)

/**
 * 用户划线/高亮笔记的 5 种效果（5x1 互斥）：单实线/波浪线/虚线/背景色/字体色。
 *
 * 样式即类型：book_marks 不再存 kind 列，效果由 [TextProcessStyle] 推导/生成，
 * 渲染引擎按 styleJson 画线，效果本身与「划线 vs 高亮」的老二元 kind 等价。
 */
@Keep
enum class MarkingEffect {
    SOLID, WAVE, DASHED, HIGHLIGHTER, BG, TEXT;

    /**
     * 是否沿用上一次的线宽/线偏移/SVG 路径（编辑时不被重置）。
     *
     * [HIGHLIGHTER] 虽然也走 underlineMode=1 渲染，但它的粗细与偏移是效果本身的定义
     * （见 [HIGHLIGHTER_WIDTH] / [HIGHLIGHTER_OFFSET]），必须由 [toStyle] 说了算，
     * 所以不算在内，否则会被旧样式的 1dp/2dp 覆盖、退化成普通细实线。
     */
    val isUnderline: Boolean
        get() = this == SOLID || this == WAVE || this == DASHED

    /**
     * 由效果 + 选中颜色生成样式。背景色自动半透明（约 20% alpha），
     * 避免不透明背景盖住正文；下划线/字体色用原色。
     *
     * [HIGHLIGHTER] 是荧光笔：一条很粗、半透明的实线，靠负偏移抬到文字上压住下半截，
     * 像马克笔扫过一道。画在文字层之下（见 TextChapterLayout.toCharStyle），
     * 所以文字压在色带上面，跟真荧光笔涂在印好的字上一样。
     */
    fun toStyle(color: Int): TextProcessStyle = when (this) {
        SOLID -> TextProcessStyle(underlineMode = 1, underlineColor = color)
        WAVE -> TextProcessStyle(underlineMode = 3, underlineColor = color)
        DASHED -> TextProcessStyle(underlineMode = 2, underlineColor = color)
        HIGHLIGHTER -> TextProcessStyle(
            underlineMode = 1,
            underlineColor = (color and 0x00FFFFFF) or HIGHLIGHTER_ALPHA,
            underlineWidth = HIGHLIGHTER_WIDTH,
            underlineOffset = HIGHLIGHTER_OFFSET,
        )

        BG -> TextProcessStyle(bgColor = (color and 0x00FFFFFF) or 0x33000000)
        TEXT -> TextProcessStyle(textColor = color)
    }

    companion object {
        /** 标记默认颜色（绿色）。 */
        const val DEFAULT_COLOR = 0xFF63C37D.toInt()

        /** 荧光笔线宽（dp）：要足够粗才盖得住半截文字。 */
        const val HIGHLIGHTER_WIDTH = 11f

        /** 荧光笔纵向偏移（dp）：负值把线从行底抬进文字里。 */
        const val HIGHLIGHTER_OFFSET = -7f

        /** 荧光笔 alpha（约 35%）：压在文字上还能看清字。 */
        const val HIGHLIGHTER_ALPHA = 0x59000000

        /** 判定荧光笔的线宽下限：普通实线是 1dp 量级，不会误判。 */
        private const val HIGHLIGHTER_WIDTH_MIN = 8f

        /**
         * 从样式反推效果：编辑已有标记时预填效果格。未知下划线模式回退单实线。
         * 荧光笔与单实线同为 underlineMode=1，靠线宽区分，故必须先判它。
         */
        fun fromStyle(style: TextProcessStyle?): MarkingEffect = when {
            style?.underlineMode == 1 && style.underlineWidth >= HIGHLIGHTER_WIDTH_MIN -> HIGHLIGHTER
            style?.underlineMode == 1 -> SOLID
            style?.underlineMode == 3 -> WAVE
            style?.underlineMode == 2 -> DASHED
            style?.bgColor != null -> BG
            style?.textColor != null -> TEXT
            else -> SOLID
        }

        /**
         * 取样式的「展示色」：下划线取线色，背景取底色，字体取字色。
         * 一律剥掉 alpha 补成不透明，让色板显示用户当初选的纯色
         * （荧光笔与背景色存的是半透明值）。
         */
        fun colorOf(style: TextProcessStyle?): Int = when {
            style?.underlineColor != null ->
                (style.underlineColor and 0x00FFFFFF) or 0xFF000000.toInt()

            style?.bgColor != null -> (style.bgColor and 0x00FFFFFF) or 0xFF000000.toInt()
            style?.textColor != null -> (style.textColor and 0x00FFFFFF) or 0xFF000000.toInt()
            else -> DEFAULT_COLOR
        }
    }
}
