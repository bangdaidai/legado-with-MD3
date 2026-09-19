package io.legado.app.feature.reader.core.model

import androidx.compose.runtime.Stable

/** Per-page decoration, so page transforms and curl clipping also apply to the bookmark. */
@Stable
data class ReaderBookmarkBadge(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Int,
    val heightPx: Int,
    val imageSource: String = "",
    val imageVersion: String = "",
) {
    companion object {
        fun create(
            hasBookmark: Boolean,
            isScroll: Boolean,
            pageWidthPx: Int,
            headerTopPx: Float,
            headerRightPaddingPx: Float,
            density: Float,
            sizeDp: Int,
            imageSource: String = "",
            imageVersion: String = "",
        ): ReaderBookmarkBadge? {
            // A zero-size badge explicitly hides the decoration while preserving the bookmark.
            if (!hasBookmark || isScroll || sizeDp <= 0) return null
            val width = (sizeDp * density).toInt().coerceAtLeast(1)
            return ReaderBookmarkBadge(
                // 角标跟随右页眉：包围盒的右上角与页眉右槽文字对齐。
                leftPx = pageWidthPx - headerRightPaddingPx - width,
                topPx = headerTopPx,
                widthPx = width,
                heightPx = width * 2,
                imageSource = imageSource,
                imageVersion = imageVersion,
            )
        }
    }
}
