package io.legado.app.feature.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderUnderlineOverflowTest {

    @Test
    fun `cap inset uses core width even when feather thickens the pass`() {
        val underline = ReaderUnderline(1, 0, widthPx = 4f, offsetPx = 2f, roundCap = true, featherPx = 5f)
        assertEquals(2f, underline.capInsetPx, 0f)
    }

    @Test
    fun `feather alone forces round cap inset`() {
        val underline = ReaderUnderline(1, 0, widthPx = 3f, offsetPx = 0f, roundCap = false, featherPx = 2f)
        assertEquals(1.5f, underline.capInsetPx, 0f)
    }

    @Test
    fun `butt cap without feather has no inset`() {
        val underline = ReaderUnderline(1, 0, widthPx = 4f, offsetPx = 0f)
        assertEquals(0f, underline.capInsetPx, 0f)
    }

    @Test
    fun `overflow pad covers offset plus half width plus feather`() {
        val underline = ReaderUnderline(1, 0, widthPx = 4f, offsetPx = 2f, roundCap = true, featherPx = 5f)
        // 下方 = offset 2 + 半宽 2 + 羽化 5 = 9；水平 = 半宽 2 + 羽化 5 = 7
        assertEquals(9f, underline.overflowPadPx, 0f)
    }

    @Test
    fun `wave and double line add their own vertical extension`() {
        // 波浪按控制点的一半留：quadTo 的中点只到控制点的 50%
        val wave = ReaderUnderline(3, 0, widthPx = 2f, offsetPx = 2f, waveControlOffsetPx = 3f)
        assertEquals(2f + 1f + 1.5f, wave.overflowPadPx, 0f)
        val double = ReaderUnderline(4, 0, widthPx = 2f, offsetPx = 2f, doubleLineGapPx = 3f)
        // 下方 = offset + 半宽 + 间隙 + 线宽
        assertEquals(2f + 1f + 3f + 2f, double.overflowPadPx, 0f)
    }

    @Test
    fun `negative offset reserves space above the text box`() {
        val underline = ReaderUnderline(1, 0, widthPx = 2f, offsetPx = -6f)
        assertEquals(6f + 1f, underline.overflowPadPx, 0f)
    }

    @Test
    fun `double line and strike ignore stored round cap and feather`() {
        // 这两个线型在编辑弹层不开放圆头/羽化，旧数据里存的值不该继续生效，
        // 否则正文会画出 UI 无法复现的效果，且与预览走偏
        listOf(4, 6).forEach { mode ->
            val underline = ReaderUnderline(
                mode, 0, widthPx = 4f, offsetPx = 2f, roundCap = true, featherPx = 3f,
            )
            assertFalse("mode $mode 不该应用圆头", underline.roundCapEffective)
            assertFalse("mode $mode 不该应用羽化", underline.featherEffective)
            // 收不到预留，也就不会多留白
            assertEquals(0f, underline.capInsetPx, 0f)
            assertEquals(2f + 2f, underline.overflowPadPx, 0f)
        }
    }

    @Test
    fun `custom svg ignores stored round cap and feather`() {
        val underline = ReaderUnderline(
            5, 0, widthPx = 4f, offsetPx = 2f, roundCap = true, featherPx = 3f,
        )

        assertFalse(underline.roundCapEffective)
        assertFalse(underline.featherEffective)
        assertEquals(0f, underline.capInsetPx, 0f)
    }

    @Test
    fun `fluorescent band still honours round cap and feather`() {
        val underline = ReaderUnderline(7, 0, widthPx = 4f, offsetPx = 2f, roundCap = true, featherPx = 3f)

        assertTrue(underline.roundCapEffective)
        assertTrue(underline.featherEffective)
    }

    @Test
    fun `content clip pad absorbs the underline overflow`() {
        val style = ReaderTextStyle(
            0xFF000000.toInt(), 20f,
            underline = ReaderUnderline(1, 0, widthPx = 4f, offsetPx = 2f, roundCap = true, featherPx = 5f),
        )
        val page = ReaderPage(
            id = ReaderPageId(0, 0),
            chapterTitle = "",
            text = "",
            widthPx = 100,
            heightPx = 100,
            contentTopPx = 0f,
            contentBottomPx = 100f,
            elements = listOf(textElement(style)),
            revision = 1L,
        )
        assertEquals(9f, page.contentClipPadPx, 0f)
    }

    private fun textElement(style: ReaderTextStyle) = ReaderElement.Text(
        bounds = ReaderRect(0f, 0f, 10f, 20f),
        baselinePx = 16f,
        value = "字",
        style = style,
        selected = false,
        emphasized = false,
        chapterPosition = 0,
    )
}
