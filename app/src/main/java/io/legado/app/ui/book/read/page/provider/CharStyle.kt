package io.legado.app.ui.book.read.page.provider

/**
 * 每个字符的高亮样式，由高亮规则匹配后填充
 */
data class CharStyle(
    val textColor: Int? = null,
    val bgColor: Int? = null,
    val underlineMode: Int = 0,
    val underlineColor: Int? = null,
    val underlineWidth: Float = 1f,
    val underlineOffset: Float = 2f,
    val underlineSvgPath: String = "",
    val underlineRoundCap: Boolean = false,
    val underlineFeather: Float = 0f,
    val bgImage: String = "",
    val bgImageFit: Int = 0,
    val bgImageScale: Float = 1f,
    val fontPath: String = "",
    val fontWeight: Int = 400,
    val isItalic: Boolean = false,
    val fontSizeOffset: Int = 0,
    val npLeft: Float = 0.5f,
    val npRight: Float = 0.5f,
    val npTop: Float = 0.5f,
    val npBottom: Float = 0.5f,
    val bgPadStart: Float = 0f,
    val bgPadEnd: Float = 0f,
    val bgPadTop: Float = 0f,
    val bgPadBottom: Float = 0f,
    val bgMarginStart: Float = 0f,
    val bgMarginEnd: Float = 0f,
    val bgMarginTop: Float = 0f,
    val bgMarginBottom: Float = 0f,
    val underlineBelowText: Boolean = false,
) {
    val hasStyle: Boolean
        get() = textColor != null || bgColor != null || underlineMode != 0 || bgImage.isNotEmpty() || fontPath.isNotEmpty() || fontWeight != 400 || isItalic || fontSizeOffset != 0
}
