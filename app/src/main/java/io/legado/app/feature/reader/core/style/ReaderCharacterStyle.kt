package io.legado.app.feature.reader.core.style

import io.legado.app.feature.reader.core.model.ReaderTextBackgroundImage
import io.legado.app.feature.reader.core.model.ReaderUnderline

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
    /** Build the winning style once per interval instead of scanning every range for each glyph. */
    fun compile(ranges: List<ReaderStyleRange>): ReaderCompiledStyleRanges {
        val boundaries = ranges.asSequence()
            .filter { it.start < it.endExclusive }
            .flatMap { sequenceOf(it.start, it.endExclusive) }
            .distinct()
            .sorted()
            .toList()
            .toIntArray()
        val bodyStyles = Array<ReaderCharacterStyle?>(boundaries.size.coerceAtLeast(1) - 1) {
            resolve(ranges, boundaries[it], false)
        }
        val titleStyles = Array<ReaderCharacterStyle?>(bodyStyles.size) {
            resolve(ranges, boundaries[it], true)
        }
        return ReaderCompiledStyleRanges(boundaries, bodyStyles, titleStyles)
    }

    /**
     * 同一位置命中多条规则/笔记时按优先级从低到高折叠：高优先级样式只覆盖自己
     * 显式设置的属性，未设置（null / 0）的属性沿用低优先级样式，让不同规则的
     * 不同属性可以叠加（对照旧引擎 cac980a64 的逐属性合并语义）。
     * [compile] 会按区间边界缓存本函数结果，逐字形查询走二分，不再每次扫全表。
     */
    fun resolve(ranges: List<ReaderStyleRange>, position: Int, isTitle: Boolean): ReaderCharacterStyle? =
        ranges.withIndex()
            .filter { it.value.contains(position, isTitle) }
            .sortedWith(compareBy({ it.value.priority }, { it.index }))
            .map { it.value.style }
            .reduceOrNull { base, override -> base.mergedUnder(override) }
}

class ReaderCompiledStyleRanges internal constructor(
    private val boundaries: IntArray,
    private val bodyStyles: Array<ReaderCharacterStyle?>,
    private val titleStyles: Array<ReaderCharacterStyle?>,
) {
    fun resolve(position: Int, isTitle: Boolean): ReaderCharacterStyle? {
        var low = 0
        var high = boundaries.size - 2
        while (low <= high) {
            val middle = (low + high) ushr 1
            when {
                position < boundaries[middle] -> high = middle - 1
                position >= boundaries[middle + 1] -> low = middle + 1
                else -> return if (isTitle) titleStyles[middle] else bodyStyles[middle]
            }
        }
        return null
    }
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
