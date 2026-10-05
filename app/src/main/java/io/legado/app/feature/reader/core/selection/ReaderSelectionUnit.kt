package io.legado.app.feature.reader.core.selection

/**
 * 长按正文时选区的自动扩展粒度。
 *
 * 排版层把正文切成 grapheme 级的 `ReaderElement.Text`，长按落点先按
 * [ReaderSelectionPolicy.snapToText] 吸附到一个元素，再按这里的粒度向两侧扩到自然边界；
 * 扩完之后两个选择器把手照旧可以自由拖动（[ReaderSelection.moveEndpoint]）。
 *
 * 取值与阅读设置 `selectTextUnit` 下拉项一一对应。未知/旧值一律回落到 [WORD]，
 * 也就是本设置出现之前的长按行为。
 */
enum class ReaderSelectionUnit {
    /** 单个字（grapheme），不扩展。 */
    CHARACTER,

    /** 词/词组，按 BreakIterator 的词边界（中文里通常就是单字到双字词）。 */
    WORD,

    /** 句，按 BreakIterator 的句边界（。！？…及其收尾引号等）。 */
    SENTENCE,

    /**
     * 当前视觉行。行内左右被装订沟隔断的（双栏页面）算两段，只取命中元素所在的那一段。
     */
    LINE,

    /** 命中元素所在的自然段。 */
    PARAGRAPH,
    ;

    companion object {
        fun fromPreference(value: String): ReaderSelectionUnit = when (value) {
            "0" -> CHARACTER
            "2" -> SENTENCE
            "3" -> LINE
            "4" -> PARAGRAPH
            else -> WORD
        }
    }
}