package io.legado.app.domain.model

import io.legado.app.feature.reader.core.style.underlineControlSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkingEffectTest {

    @Test
    fun `效果到样式 - 单实线是 mode 1 加线色`() {
        val style = MarkingEffect.SOLID.toStyle(0xFFFF0000.toInt())
        assertEquals(1, style.underlineMode)
        assertEquals(0xFFFF0000.toInt(), style.underlineColor)
        assertEquals(null, style.bgColor)
        assertEquals(null, style.textColor)
        assertTrue(MarkingEffect.SOLID.isUnderline)
    }

    @Test
    fun `效果到样式 - 波浪线和虚线映射到对应 mode`() {
        assertEquals(3, MarkingEffect.WAVE.toStyle(0xFFFF0000.toInt()).underlineMode)
        assertEquals(2, MarkingEffect.DASHED.toStyle(0xFFFF0000.toInt()).underlineMode)
    }

    @Test
    fun `效果到样式 - 双实线是 mode 4 且吃线宽`() {
        val style = MarkingEffect.DOUBLE.toStyle(0xFFFF0000.toInt())
        assertEquals(4, style.underlineMode)
        assertEquals(0xFFFF0000.toInt(), style.underlineColor)
        assertTrue(MarkingEffect.DOUBLE.isUnderline)
        assertTrue(underlineControlSupport(4).width)
    }

    @Test
    fun `效果到样式 - 荧光笔不吃线宽但仍归入下划线类`() {
        // isUnderline 为 true 而 width 为 false：笔记面板据此判定不继承存量荧光笔
        // 笔记里的线宽/偏移，避免切到实线时被搬运（见 MarkingSheet 的几何门控）
        assertTrue(MarkingEffect.HIGHLIGHT.isUnderline)
        assertFalse(underlineControlSupport(7).width)
    }

    @Test
    fun `效果到样式 - 删除线与半高荧光使用独立绘制模式且荧光不附加默认透明度`() {
        val color = 0xFFFFD54F.toInt()
        assertEquals(6, MarkingEffect.STRIKE.toStyle(color).underlineMode)
        val highlight = MarkingEffect.HIGHLIGHT.toStyle(color)
        assertEquals(7, highlight.underlineMode)
        assertEquals(0xFFFFD54F.toInt(), highlight.underlineColor)
        assertTrue(MarkingEffect.STRIKE.isUnderline)
        assertTrue(MarkingEffect.HIGHLIGHT.isUnderline)
    }

    @Test
    fun `效果到样式 - 荧光保留所选颜色的透明度`() {
        val translucent = 0x40FFD54F.toInt()
        assertEquals(
            translucent,
            MarkingEffect.HIGHLIGHT.toStyle(translucent).underlineColor,
        )
    }

    @Test
    fun `效果到样式 - 背景色自动加 ~20% 透明度`() {
        val style = MarkingEffect.BG.toStyle(0xFFFFD54F.toInt())
        assertEquals(0x33FFD54F.toInt(), style.bgColor)
        assertEquals(0, style.underlineMode)
        assertFalse(MarkingEffect.BG.isUnderline)
    }

    @Test
    fun `效果到样式 - 字体色是字色`() {
        val style = MarkingEffect.TEXT.toStyle(0xFFFF0000.toInt())
        assertEquals(0xFFFF0000.toInt(), style.textColor)
        assertEquals(null, style.bgColor)
        assertEquals(0, style.underlineMode)
        assertFalse(MarkingEffect.TEXT.isUnderline)
    }

    @Test
    fun `样式到效果 - 下划线 mode 反推正确`() {
        assertEquals(
            MarkingEffect.SOLID,
            MarkingEffect.fromStyle(TextProcessStyle(underlineMode = 1))
        )
        assertEquals(
            MarkingEffect.DASHED,
            MarkingEffect.fromStyle(TextProcessStyle(underlineMode = 2))
        )
        assertEquals(
            MarkingEffect.WAVE,
            MarkingEffect.fromStyle(TextProcessStyle(underlineMode = 3))
        )
        assertEquals(
            MarkingEffect.STRIKE,
            MarkingEffect.fromStyle(TextProcessStyle(underlineMode = 6))
        )
        assertEquals(
            MarkingEffect.DOUBLE,
            MarkingEffect.fromStyle(TextProcessStyle(underlineMode = 4))
        )
        assertEquals(
            MarkingEffect.HIGHLIGHT,
            MarkingEffect.fromStyle(TextProcessStyle(underlineMode = 7))
        )
    }

    @Test
    fun `样式到效果 - 背景与字体色反推`() {
        assertEquals(
            MarkingEffect.BG,
            MarkingEffect.fromStyle(TextProcessStyle(bgColor = 0x33FFD54F.toInt()))
        )
        assertEquals(
            MarkingEffect.TEXT,
            MarkingEffect.fromStyle(TextProcessStyle(textColor = 0xFFFF0000.toInt()))
        )
    }

    @Test
    fun `样式到效果 - 自定义 SVG 与未知模式回退单实线`() {
        assertEquals(MarkingEffect.SOLID, MarkingEffect.fromStyle(TextProcessStyle()))
        assertEquals(MarkingEffect.SOLID, MarkingEffect.fromStyle(null))
        // mode 5（自定义 SVG）在效果格里没有对应格，回退单实线
        assertEquals(
            MarkingEffect.SOLID,
            MarkingEffect.fromStyle(TextProcessStyle(underlineMode = 5))
        )
    }

    @Test
    fun `展示色 - 背景剥 alpha 取底色，下划线取线色`() {
        assertEquals(
            0xFFFFD54F.toInt(),
            MarkingEffect.colorOf(TextProcessStyle(bgColor = 0x33FFD54F.toInt()))
        )
        assertEquals(
            0xFFFF0000.toInt(),
            MarkingEffect.colorOf(
                TextProcessStyle(
                    underlineMode = 1,
                    underlineColor = 0xFFFF0000.toInt()
                )
            ),
        )
        assertEquals(
            MarkingEffect.DEFAULT_COLOR,
            MarkingEffect.colorOf(null),
        )
    }
}
