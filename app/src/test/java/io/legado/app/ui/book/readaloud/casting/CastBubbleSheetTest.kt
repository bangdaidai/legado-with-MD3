package io.legado.app.ui.book.readaloud.casting

import io.legado.app.data.entities.HighlightRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastBubbleSheetTest {
    @Test
    fun `border only bubble is a visible style`() {
        val rule = HighlightRule(
            borderColor = 0xFF63C37D.toInt(),
            borderWidth = 2f,
            borderRadius = 6f,
        )

        assertFalse(rule.setsNothing())
    }

    @Test
    fun `night border only bubble is a visible style`() {
        assertFalse(HighlightRule(borderColorNight = 0xFF63C37D.toInt()).setsNothing())
    }

    @Test
    fun `empty bubble is discarded`() {
        assertTrue(HighlightRule().setsNothing())
    }
}
