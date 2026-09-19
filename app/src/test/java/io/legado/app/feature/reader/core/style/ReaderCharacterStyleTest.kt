package io.legado.app.feature.reader.core.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderCharacterStyleTest {
    @Test
    fun higherPriorityMarkingOverridesRegexStyle() {
        val ranges = listOf(
            ReaderStyleRange(0, 5, ReaderStyleTarget.BODY, ReaderCharacterStyle(colorArgb = 1), priority = 2),
            ReaderStyleRange(1, 3, ReaderStyleTarget.BODY, ReaderCharacterStyle(colorArgb = 2, markingId = "m"), priority = 10_000),
        )
        assertEquals(1, ReaderCharacterStyleResolver.resolve(ranges, 0, false)?.colorArgb)
        assertEquals("m", ReaderCharacterStyleResolver.resolve(ranges, 2, false)?.markingId)
        assertNull(ReaderCharacterStyleResolver.resolve(ranges, 2, true))
    }

    @Test
    fun overlappingRangesMergePropertiesInsteadOfReplacing() {
        val image = ReaderTextBackgroundImage("frame.png", 3, 1f)
        val ranges = listOf(
            ReaderStyleRange(
                0, 8, ReaderStyleTarget.BODY,
                ReaderCharacterStyle(
                    backgroundArgb = 11, fontPath = "f",
                    fontSizeOffsetPx = 4f, backgroundImage = image,
                ),
                priority = 0,
            ),
            ReaderStyleRange(
                2, 4, ReaderStyleTarget.BODY,
                ReaderCharacterStyle(backgroundArgb = 22, markingId = "m"),
                priority = 10_000,
            ),
        )
        // 高优先级笔记只覆盖自己显式设置的属性，规则的背景图/字体/字号偏移存活
        val merged = ReaderCharacterStyleResolver.resolve(ranges, 3, false)!!
        assertEquals(22, merged.backgroundArgb)
        assertEquals("f", merged.fontPath)
        assertEquals(4f, merged.fontSizeOffsetPx, 0f)
        assertEquals(image, merged.backgroundImage)
        assertEquals("m", merged.markingId)
        // 笔记范围外仍只有规则自己的属性
        val ruleOnly = ReaderCharacterStyleResolver.resolve(ranges, 0, false)!!
        assertEquals(11, ruleOnly.backgroundArgb)
        assertNull(ruleOnly.markingId)
    }
}
