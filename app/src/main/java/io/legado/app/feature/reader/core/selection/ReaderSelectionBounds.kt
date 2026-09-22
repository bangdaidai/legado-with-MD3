package io.legado.app.feature.reader.core.selection

import io.legado.app.feature.reader.core.model.ReaderElement
import io.legado.app.feature.reader.core.model.ReaderRect
/** Joins sequential glyph boxes on the same visual line into one continuous selection band. */
fun List<ReaderRect>.mergeSelectionBounds(minimumLineOverlap: Float = 0.5f): List<ReaderRect> {
    if (size < 2) return this
    val result = ArrayList<ReaderRect>(size)
    forEach { rect ->
        val previous = result.lastOrNull()
        val verticalOverlap = previous?.let {
            (minOf(it.bottom, rect.bottom) - maxOf(it.top, rect.top)).coerceAtLeast(0f)
        } ?: 0f
        val sameLine = previous != null && verticalOverlap >=
            minOf(previous.height, rect.height) * minimumLineOverlap.coerceIn(0f, 1f)
        val joinGapPx = previous?.let { minOf(it.height, rect.height) * 0.5f } ?: 0f
        val horizontalGap = previous?.let {
            maxOf(rect.left - it.right, it.left - rect.right, 0f)
        } ?: Float.POSITIVE_INFINITY
        if (sameLine && horizontalGap <= joinGapPx) {
            result[result.lastIndex] = ReaderRect(
                left = minOf(previous.left, rect.left),
                top = minOf(previous.top, rect.top),
                right = maxOf(previous.right, rect.right),
                bottom = maxOf(previous.bottom, rect.bottom),
            )
        } else {
            result += rect
        }
    }
    return result
}

/**
 * 实时样式预览的落位矩形。段首空白在排版期就不吃装饰（旧 `clearLeadingWhitespaceStyles`
 * 的口径，跨段笔记同样跳过），预览必须按同一规则筛字，否则选区是连续区间、
 * 而正文是逐字样式——段首那截缩进会在预览里凭空多出一条线。
 */
fun ReaderSelection.stylePreviewBounds(
    elements: List<ReaderElement.Text>,
    pageChapterIndex: Int,
): List<ReaderRect> = elements
    .filter { contains(it, pageChapterIndex) && !it.decorationExempt }
    .map(ReaderElement.Text::bounds)
    .mergeSelectionBounds()
