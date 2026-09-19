package io.legado.app.feature.reader.core.model

import kotlin.math.abs

data class ReaderTextBackgroundRun(
    val contentBounds: ReaderRect,
    val image: ReaderTextBackgroundImage,
)

fun ReaderPage.textBackgroundRuns(): List<ReaderTextBackgroundRun> {
    val runs = mutableListOf<ReaderTextBackgroundRun>()
    // 行级合并对照旧 View TextLine.drawStyledBackgrounds：同一行内相邻且同背景图
    // 的字合并为一段，一次性绘制。元素相邻是硬条件（未被匹配的字会打断 run）；
    // 字间距/两端对齐产生的间隙由分页期标记 continuesBackgroundRun 放行，既避免
    // 逐字渲染背景，也不会把跨栏/跨行或隔着未匹配文字的同图段错误拼接。
    // 九宫格（fit==3）的外扩框不再预存：绘制期用 NinePatchDrawHelper.layout
    // 从文字矩形现算，与规则编辑预览完全同一套几何。
    var previousElement: ReaderElement? = null
    elements.forEach { element ->
        val text = element as? ReaderElement.Text
        if (text == null) {
            previousElement = element
            return@forEach
        }
        val image = text.style.backgroundImage
        if (image == null) {
            previousElement = text
            return@forEach
        }
        val previous = runs.lastOrNull()
        val previousIsSameImage = (previousElement as? ReaderElement.Text)
            ?.style?.backgroundImage == image
        val sameRow = previous != null &&
            abs(previous.contentBounds.top - text.bounds.top) < 0.5f &&
                abs(previous.contentBounds.bottom - text.bounds.bottom) < 0.5f
        val contiguous = previous != null &&
            abs(previous.contentBounds.right - text.bounds.left) < 1f
        if (
            previous != null && previousIsSameImage && sameRow &&
            (contiguous || text.continuesBackgroundRun)
        ) {
            runs[runs.lastIndex] = previous.copy(
                contentBounds = previous.contentBounds.copy(right = text.bounds.right),
            )
        } else {
            runs += ReaderTextBackgroundRun(
                contentBounds = text.bounds,
                image = image,
            )
        }
        previousElement = text
    }
    return runs
}
