package io.legado.app.feature.reader.core.style

import io.legado.app.feature.reader.core.model.ReaderUnderline
import io.legado.app.feature.reader.core.model.ReaderTextBackgroundImage

enum class ReaderStyleTarget { ALL, TITLE, BODY }

data class ReaderCharacterStyle(
    val colorArgb: Int? = null,
    val backgroundArgb: Int? = null,
    val underline: ReaderUnderline? = null,
    val fontPath: String? = null,
    val fontWeight: Int? = null,
    val italic: Boolean? = null,
    val fontSizeOffsetPx: Float = 0f,
    val markingId: String? = null,
    val backgroundImage: ReaderTextBackgroundImage? = null,
)

data class ReaderStyleRange(
    val start: Int,
    val endExclusive: Int,
    val target: ReaderStyleTarget,
    val style: ReaderCharacterStyle,
    val priority: Int = 0,
) {
    fun contains(position: Int, isTitle: Boolean): Boolean =
        position in start until endExclusive && when (target) {
            ReaderStyleTarget.ALL -> true
            ReaderStyleTarget.TITLE -> isTitle
            ReaderStyleTarget.BODY -> !isTitle
        }
}

object ReaderCharacterStyleResolver {
    /**
     * 同一位置命中多条规则/笔记时按优先级从低到高折叠：高优先级样式只覆盖自己
     * 显式设置的属性，未设置（null / 0）的属性沿用低优先级样式，让不同规则的
     * 不同属性可以叠加（对照旧引擎 cac980a64 的逐属性合并语义）。
     */
    fun resolve(ranges: List<ReaderStyleRange>, position: Int, isTitle: Boolean): ReaderCharacterStyle? =
        ranges.withIndex()
            .filter { it.value.contains(position, isTitle) }
            .sortedWith(compareBy({ it.value.priority }, { it.index }))
            .map { it.value.style }
            .reduceOrNull { base, override -> base.mergedUnder(override) }
}

private fun ReaderCharacterStyle.mergedUnder(override: ReaderCharacterStyle) = copy(
    colorArgb = override.colorArgb ?: colorArgb,
    backgroundArgb = override.backgroundArgb ?: backgroundArgb,
    underline = override.underline ?: underline,
    fontPath = override.fontPath ?: fontPath,
    fontWeight = override.fontWeight ?: fontWeight,
    italic = override.italic ?: italic,
    fontSizeOffsetPx = if (override.fontSizeOffsetPx != 0f) override.fontSizeOffsetPx else fontSizeOffsetPx,
    markingId = override.markingId ?: markingId,
    backgroundImage = override.backgroundImage ?: backgroundImage,
)
