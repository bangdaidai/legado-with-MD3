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
 * 用户划线/高亮笔记的互斥效果。
 *
 * 样式即类型：book_marks 不再存 kind 列，效果由 [TextProcessStyle] 推导/生成，
 * 渲染引擎按 styleJson 画线，效果本身与「划线 vs 高亮」的老二元 kind 等价。
 */
@Keep
enum class MarkingEffect(val underlineMode: Int = 0) {
    SOLID(1), WAVE(3), DASHED(2), STRIKE(6), DOUBLE(4), HIGHLIGHT(7), BG, TEXT;

    /**
     * 是否属于下划线类效果（对应 [TextProcessStyle.underlineMode] != 0）。
     *
     * [STRIKE] 属于此列，但不在笔记面板的效果格里（划线笔记里很少用），见
     * `MarkingSheet.SelectableEffects`；它只用于解析存量笔记，保留是为了不把
     * 已有的删除线笔记降级成实线。
     */
    val isUnderline: Boolean
        get() = underlineMode != 0

    /**
     * 该效果是否真的消费 [TextProcessStyle.underlineWidth]。
     *
     * 与 [isUnderline] **不是一回事**，这是笔记样式污染的根源：
     * [HIGHLIGHT] 属于下划线类（styleJson 里确实带 mode 7），但它是铺下半行的
     * **填充色带**，不是描边——渲染层压根不读线宽，所以对荧光笔来说
     * `underlineWidth` 是个没有任何含义的字段。而 [BG]/[TEXT] 连下划线都没有。
     *
     * 判定放在这里而不是让 UI 去问渲染层（`ReaderUnderlineGeometry.underlineControlSupport`）：
     * 那是 feature 层，domain 不得反向依赖。「哪些参数对某线型生效」是效果自身的
     * 语义，属于领域知识。两侧一致性由 `MarkingEffectTest` 钉住：改了任一侧而忘了
     * 另一侧，测试即红。
     *
     * 用途：笔记面板只在这条为真时才允许继承存量样式里的线宽/偏移；否则一律回规范值，
     * 免得早期「荧光笔粗细/偏移」时代留下的脏值被搬到实线/波浪等真吃线宽的线型上。
     */
    val consumesUnderlineWidth: Boolean
        get() = when (this) {
            SOLID, WAVE, DASHED, STRIKE, DOUBLE -> true
            HIGHLIGHT, BG, TEXT -> false
        }

    /**
     * 由效果 + 选中颜色生成样式。背景色自动半透明（约 20% alpha），
     * 避免不透明背景盖住正文；下划线/荧光/字体色用原色，透明度由所选颜色的
     * alpha 决定，不再附加默认透明度。
     */
    fun toStyle(color: Int): TextProcessStyle = when (this) {
        SOLID, WAVE, DASHED, STRIKE, DOUBLE, HIGHLIGHT -> TextProcessStyle(
            underlineMode = underlineMode,
            underlineColor = color,
        )

        BG -> TextProcessStyle(bgColor = (color and 0x00FFFFFF) or 0x33000000)
        TEXT -> TextProcessStyle(textColor = color)
    }

    companion object {
        /** 标记默认颜色（绿色）。 */
        const val DEFAULT_COLOR = 0xFF63C37D.toInt()

        /**
         * 从样式反推效果：编辑已有标记时预填效果格（[SelectableEffects]，7 格）。
         *
         * 按 [underlineMode] 反查，查不到再回退背景色/字体色：mode 5（自定义 SVG）
         * 在效果格里没有对应格，回退单实线——枚举里没有它，反推时不能匹配到任何项。
         */
        fun fromStyle(style: TextProcessStyle?): MarkingEffect {
            val mode = style?.underlineMode ?: 0
            val byMode = entries.firstOrNull { it.underlineMode == mode && mode != 0 }
            return byMode
                ?: when {
                    style?.bgColor != null -> BG
                    style?.textColor != null -> TEXT
                    else -> SOLID
                }
        }

        /** 取样式的「展示色」：下划线取线色，背景剥掉 alpha 取底色，字体取字色。 */
        fun colorOf(style: TextProcessStyle?): Int = when {
            style?.underlineColor != null -> style.underlineColor
            style?.bgColor != null -> (style.bgColor and 0x00FFFFFF) or 0xFF000000.toInt()
            style?.textColor != null -> style.textColor
            else -> DEFAULT_COLOR
        }
    }
}
