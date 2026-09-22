package io.legado.app.feature.reader.core.selection

import io.legado.app.feature.reader.core.model.ReaderElement
import io.legado.app.feature.reader.core.model.ReaderRect
import io.legado.app.feature.reader.core.model.ReaderTextStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSelectionBoundsTest {
    @Test
    fun `adjacent glyph bounds on one line become a continuous band`() {
        val merged = listOf(
            ReaderRect(10f, 20f, 20f, 40f),
            ReaderRect(20f, 20f, 31f, 40f),
            ReaderRect(31.5f, 20f, 42f, 40f),
        ).mergeSelectionBounds()

        assertEquals(listOf(ReaderRect(10f, 20f, 42f, 40f)), merged)
    }

    @Test
    fun `selection bands remain separate across visual lines`() {
        val merged = listOf(
            ReaderRect(10f, 20f, 20f, 40f),
            ReaderRect(10f, 45f, 20f, 65f),
        ).mergeSelectionBounds()

        assertEquals(2, merged.size)
    }

    @Test
    fun `letter spacing is included in a continuous selection band`() {
        val merged = listOf(
            ReaderRect(10f, 20f, 20f, 40f),
            ReaderRect(26f, 20f, 36f, 40f),
        ).mergeSelectionBounds()

        assertEquals(listOf(ReaderRect(10f, 20f, 36f, 40f)), merged)
    }

    @Test
    fun `large same-row column gap is not selected`() {
        val merged = listOf(
            ReaderRect(10f, 20f, 20f, 40f),
            ReaderRect(80f, 20f, 90f, 40f),
        ).mergeSelectionBounds()

        assertEquals(2, merged.size)
    }

    @Test
    fun `different font metrics on one visual line still form one band`() {
        val merged = listOf(
            ReaderRect(10f, 20f, 24f, 44f),
            ReaderRect(24f, 25f, 34f, 41f),
        ).mergeSelectionBounds()

        assertEquals(listOf(ReaderRect(10f, 20f, 34f, 44f)), merged)
    }

    @Test
    fun `right to left visual fragments expand the band in both directions`() {
        val merged = listOf(
            ReaderRect(30f, 20f, 40f, 40f),
            ReaderRect(20f, 20f, 30f, 40f),
        ).mergeSelectionBounds()

        assertEquals(listOf(ReaderRect(20f, 20f, 40f, 40f)), merged)
    }

    @Test
    fun `style preview starts after the leading whitespace the body leaves undecorated`() {
        // 跨段笔记的选区是连续区间，正文却是逐字样式：下一段的段首空白在排版期就不吃
        // 装饰。预览若照抄选区矩形，点开笔记时那截缩进会凭空多出一条下划线。
        val elements = listOf(
            text("甲", 0, ReaderRect(10f, 20f, 40f, 40f)),
            text("　", 1, ReaderRect(10f, 45f, 20f, 65f), exempt = true),
            text("乙", 2, ReaderRect(20f, 45f, 30f, 65f)),
        )

        assertEquals(
            listOf(ReaderRect(10f, 20f, 40f, 40f), ReaderRect(20f, 45f, 30f, 65f)),
            ReaderSelection(1, 0, 2).stylePreviewBounds(elements, 1),
        )
        // 灰色选区底色仍按整行覆盖，不受装饰豁免影响
        assertEquals(
            listOf(ReaderRect(10f, 20f, 40f, 40f), ReaderRect(10f, 45f, 30f, 65f)),
            elements.filter { ReaderSelection(1, 0, 2).contains(it, 1) }
                .map(ReaderElement.Text::bounds).mergeSelectionBounds(),
        )
    }

    private fun text(
        value: String,
        chapterPosition: Int,
        bounds: ReaderRect,
        exempt: Boolean = false,
    ) = ReaderElement.Text(
        bounds = bounds,
        baselinePx = bounds.bottom,
        value = value,
        style = ReaderTextStyle(0xFF000000.toInt(), 20f),
        selected = false,
        emphasized = false,
        chapterPosition = chapterPosition,
        decorationExempt = exempt,
    )
}
