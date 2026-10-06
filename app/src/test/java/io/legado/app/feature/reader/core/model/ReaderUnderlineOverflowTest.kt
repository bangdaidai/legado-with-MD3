package io.legado.app.feature.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderUnderlineOverflowTest {

    @Test
    fun `corner inset is the shared radius capped at half the line width`() {
        val underline = ReaderUnderline(1, 0, widthPx = 4f, offsetPx = 2f, featherPx = 5f)
        // 统一圆角 3px：线宽 4px 时取半宽 2px
        assertEquals(2f, underline.capInsetPx(4f, 3f), 0f)
        // 加粗趟（羽化外圈 14px）最多取自己的半宽 7px
        assertEquals(3f, underline.capInsetPx(14f, 3f), 0f)
        // 亚像素线宽抬到 1px 后取半宽
        assertEquals(0.5f, underline.capInsetPx(0.2f, 3f), 0f)
    }

    @Test
    fun `stored round cap no longer changes anything`() {
        // 端点圆角开关已移除：开与关必须完全一致，否则旧数据会画出不同观感
        val on = ReaderUnderline(1, 0, widthPx = 4f, offsetPx = 0f, roundCap = true, featherPx = 3f)
        val off = ReaderUnderline(1, 0, widthPx = 4f, offsetPx = 0f, featherPx = 3f)
        assertEquals(on.overflowPadPx, off.overflowPadPx, 0f)
        assertEquals(on.capInsetPx(4f, 3f), off.capInsetPx(4f, 3f), 0f)
    }

    @Test
    fun `feather alone does not change the corner inset`() {
        val underline = ReaderUnderline(1, 0, widthPx = 3f, offsetPx = 0f, featherPx = 2f)
        assertTrue(underline.featherEffective)
        // 羽化不参与收边判定：圆角半径只由统一常量和线宽决定
        assertEquals(1.5f, underline.capInsetPx(3f, 3f), 0f)
    }

    @Test
    fun `plain stroke reserves no room beyond half its width`() {
        val underline = ReaderUnderline(1, 0, widthPx = 4f, offsetPx = 0f)
        assertEquals(2f, underline.overflowPadPx, 0f)
    }

    @Test
    fun `overflow pad covers offset plus half width plus feather`() {
        val underline = ReaderUnderline(1, 0, widthPx = 4f, offsetPx = 2f, featherPx = 5f)
        // 下方 = offset 2 + 半宽 2 + 羽化 5 = 9；横向同样是半宽 + 羽化 = 7
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
    fun `wave troughs reaching above the baseline reserve the wave height`() {
        // 波谷向上探出实际峰高（控制点的一半）：描边带围绕路径 ±羽化，
        // 包络上缘 = -(峰高 + 半宽 + 羽化)，与下方对称，裁剪才不会切平波谷。
        val wave = ReaderUnderline(3, 0, widthPx = 2f, offsetPx = -2f, waveControlOffsetPx = 3f)
        // 上方 = |offset| 2 + 半宽 1 + 峰高 1.5 = 4.5；下方 = 半宽 1 + 峰高 1.5 = 2.5
        assertEquals(2f + 1f + 1.5f, wave.overflowPadPx, 0f)
    }

    @Test
    fun `double line strike and svg ignore stored feather`() {
        // 这三个线型在编辑弹层不开放羽化，旧数据里存的值不该继续生效，
        // 否则正文会画出 UI 无法复现的效果，且与预览走偏
        listOf(4, 5, 6).forEach { mode ->
            val underline = ReaderUnderline(
                mode, 0, widthPx = 4f, offsetPx = 2f, roundCap = true, featherPx = 3f,
            )
            assertFalse("mode $mode 不该应用羽化", underline.featherEffective)
            assertEquals(2f + 2f, underline.overflowPadPx, 0f)
        }
    }

    @Test
    fun `fluorescent band still honours feather but never pads horizontally`() {
        val underline = ReaderUnderline(7, 0, widthPx = 4f, offsetPx = 2f, featherPx = 3f)

        assertTrue(underline.featherEffective)
        // drawRoundRect 的圆角切在矩形内部、绝不外扩，所以横向不留白
        assertEquals(2f + 3f, underline.overflowPadPx, 0f)
    }

    @Test
    fun `content clip pad absorbs the underline overflow`() {
        val style = ReaderTextStyle(
            0xFF000000.toInt(), 20f,
            underline = ReaderUnderline(1, 0, widthPx = 4f, offsetPx = 2f, featherPx = 5f),
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
