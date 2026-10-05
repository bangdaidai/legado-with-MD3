package io.legado.app.feature.reader.core.selection

import io.legado.app.feature.reader.core.model.ReaderRect

data class ReaderSelectionMenuAnchor(
    val startX: Float,
    val startTopY: Float,
    val startBottomY: Float,
    val endX: Float,
    val endBottomY: Float,
) {
    companion object {
        fun from(bounds: List<ReaderRect>): ReaderSelectionMenuAnchor? {
            val first = bounds.firstOrNull() ?: return null
            val last = bounds.last()
            return ReaderSelectionMenuAnchor(
                startX = first.left,
                startTopY = first.top,
                startBottomY = first.bottom,
                endX = last.right,
                endBottomY = last.bottom,
            )
        }

        /**
         * 单个矩形（如笔记角标）当作锚点：退化成一个「只有一行、左右同宽」的选区，
         * 于是浮窗能直接复用选区浮层的落位规则（[io.legado.app.ui.book.read.TextMenuPositionProvider]），
         * 不必为角标再写一套定位。
         */
        fun of(bounds: ReaderRect): ReaderSelectionMenuAnchor = ReaderSelectionMenuAnchor(
            startX = bounds.left,
            startTopY = bounds.top,
            startBottomY = bounds.bottom,
            endX = bounds.right,
            endBottomY = bounds.bottom,
        )
    }
}
