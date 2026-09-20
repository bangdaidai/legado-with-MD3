package io.legado.app.feature.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderBookmarkBadgeTest {
    private fun badge(
        bookmarked: Boolean = true,
        scroll: Boolean = false,
        size: Int = 10,
        rightTipWidthPx: Float = 0f,
    ) = ReaderBookmarkBadge.create(
        bookmarked, scroll, 600, 100f, 30f, 2f, size, rightTipWidthPx = rightTipWidthPx
    )

    @Test
    fun topRightCornerAnchorsToHeaderAndPreservesRibbonRatio() {
        assertEquals(ReaderBookmarkBadge(550f, 100f, 20, 40), badge())
        assertEquals(ReaderBookmarkBadge(530f, 100f, 40, 80), badge(size = 20))
    }

    @Test
    fun hiddenWithoutBookmarkInScrollModeOrAtZeroSize() {
        assertNull(badge(bookmarked = false))
        assertNull(badge(scroll = true))
        assertNull(badge(size = 0))
    }

    @Test
    fun badgeShiftsLeftOnlyWhenRightHeaderTipPresent() {
        val shifted = ReaderBookmarkBadge.create(
            hasBookmark = true,
            isScroll = false,
            pageWidthPx = 600,
            headerTopPx = 100f,
            headerRightPaddingPx = 30f,
            density = 2f,
            sizeDp = 10,
            rightTipWidthPx = 60f,
            rightTipGapPx = 8f,
        )
        // 600 - 30 - (60 + 8) - 20：有右页眉文字时整体左移留出间距。
        assertEquals(482f, shifted!!.leftPx)
        // 页眉右槽为空时保持原位。
        assertEquals(550f, badge(rightTipWidthPx = 0f)?.leftPx)
    }

    @Test
    fun nonPositiveSizeDoesNotCreateBadge() {
        assertNull(badge(size = -10))
    }

    @Test fun customImageVersionParticipatesInPageStateEquality() {
        val first = badge()!!.copy(imageSource = "badge.png", imageVersion = "1")
        val second = first.copy(imageVersion = "2")
        assertNotEquals(ReaderPageDecoration(bookmarkBadge = first), ReaderPageDecoration(bookmarkBadge = second))
        assertNotEquals(ReaderPageDecoration(bookmarkBadge = first), ReaderPageDecoration())
    }
}
