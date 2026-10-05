package io.legado.app.feature.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 笔记角标的落位与命中。落位口径：**贴末字右侧**、**纵向统一压行盒底边**（所有线型的
 * 图标都落在样式右下角同一高度）、**不收口**（可以探出页边），命中区是绘制矩形外扩到
 * 24dp（横向只在圆左边多让 4dp）。
 */
class ReaderMarkingNoteBadgeTest {
    private val density = 2f
    private val style = ReaderTextStyle(0xFF000000.toInt(), 20f)
    private val accent = 0xFF00FF00.toInt()

    private fun badges(
        vararg elements: ReaderElement,
        notes: Map<String, String>,
    ) = ReaderMarkingNoteBadge.createAll(
        page = page(*elements),
        notes = notes,
        badgeColorOf = { accent },
        density = density,
    )

    @Test
    fun `markings without a note get no badge`() {
        val element = text(0f, 0f, 20f, 30f, chapterPosition = 0, markingId = "m1")
        assertTrue(badges(element, notes = emptyMap()).isEmpty())
        // 备注存在但为空串：同样没有可看的备注，不出角标。
        assertTrue(badges(element, notes = mapOf("m1" to "  ")).isEmpty())
        // 快照里有这条备注，但本页没有对应划线（备注在别的页）。
        assertTrue(badges(notes = mapOf("m1" to "note")).isEmpty())
    }

    @Test
    fun `badge follows the last line and its center sits on the last glyph edge`() {
        val badges = badges(
            text(0f, 0f, 20f, 30f, chapterPosition = 0, markingId = "m1"),
            text(0f, 30f, 40f, 60f, chapterPosition = 5, markingId = "m1"),
            notes = mapOf("m1" to "note"),
        )

        // 12dp 直径 @2x = 24px，圆心压在末字右边界 40 上 → 横向 (28,52)：
        // 一半盖住末字、一半在外侧。纵向统一压行盒底边 60。
        assertEquals(1, badges.size)
        assertEquals(ReaderRect(28f, 48f, 52f, 72f), badges.single().bounds)
    }

    @Test
    fun `badge sits on the line bottom instead of following the underline`() {
        val underline = ReaderUnderline(1, 0xFFFF0000.toInt(), 1f, offsetPx = 4f)
        val badges = badges(
            text(0f, 30f, 40f, 60f, chapterPosition = 0, markingId = "m1", style = marked(underline)),
            notes = mapOf("m1" to "note"),
        )

        // 所有线型统一：圆心压行盒底边 60；下划线画在 60+4=64 处，圆心贴样式底部、
        // 不跟着下划线的下沉量走。
        val bounds = badges.single().bounds
        assertEquals(60f, bounds.top + bounds.height / 2f)
        assertEquals(40f, bounds.left + bounds.width / 2f)
        assertEquals(ReaderRect(28f, 48f, 52f, 72f), bounds)
    }

    @Test
    fun `fluorescent badge sits on the band bottom instead of the band centre`() {
        // 荧光色带铺行盒下半行，色带中点在 30 + 30*0.75 = 52.5；圆心改压样式
        // 底边（= 行盒底 60），与背景色/描边类落在同一条线上。
        val underline = ReaderUnderline(7, 0xFFFF0000.toInt(), 1f, offsetPx = 4f)
        val badges = badges(
            text(0f, 30f, 40f, 60f, chapterPosition = 0, markingId = "m1", style = marked(underline)),
            notes = mapOf("m1" to "note"),
        )

        assertEquals(ReaderRect(28f, 48f, 52f, 72f), badges.single().bounds)
    }

    @Test
    fun `badge is not clamped to the page edge`() {
        val underline = ReaderUnderline(1, 0xFFFF0000.toInt(), 1f, offsetPx = 4f)
        val badges = badges(
            text(0f, 0f, 100f, 30f, chapterPosition = 0, markingId = "m1", style = marked(underline)),
            notes = mapOf("m1" to "note"),
        )

        // 页宽只有 100，圆心在末字末端 100，圆有 12px 探出页边；不做任何收口。
        assertEquals(ReaderRect(88f, 18f, 112f, 42f), badges.single().bounds)
    }

    @Test
    fun `one badge per marking and the hit box grows mostly to the right`() {
        val badges = badges(
            text(0f, 0f, 20f, 30f, chapterPosition = 0, markingId = "m1"),
            text(0f, 0f, 20f, 30f, chapterPosition = 0, markingId = "m2"),
            notes = mapOf("m1" to "a", "m2" to "b"),
        )

        assertEquals(listOf("m1", "m2"), badges.map { it.markingId })
        // 圆 (8,18)-(32,42)；触控区 48px 见方（24dp），竖向以圆心对称；横向左沿在圆左边
        // 再多 4dp（8px）到 0，右沿到 48——只比圆本身盖住末字的 12px 多吃 8px。
        assertEquals(ReaderRect(0f, 6f, 48f, 54f), badges.first().hitBounds)
    }

    @Test
    fun `hit test only fires inside the hit box`() {
        val underline = ReaderUnderline(1, 0xFFFF0000.toInt(), 1f, offsetPx = 4f)
        val source = page(
            text(40f, 30f, 60f, 60f, chapterPosition = 0, markingId = "m1", style = marked(underline)),
        )
        val page = source.copy(
            decoration = ReaderPageDecoration(
                markingNoteBadges = ReaderMarkingNoteBadge.createAll(
                    page = source,
                    notes = mapOf("m1" to "note"),
                    badgeColorOf = { accent },
                    density = density,
                ),
            ),
        )

        // 圆 (48,48)-(72,72)、圆心 (60,60)；触控区 (40,36)-(88,84)：横向左沿比圆
        // 左边只多 4dp（8px），其余往右边的空白里长。
        assertEquals("m1", page.markingNoteBadgeAt(60f, 64f)?.markingId)
        assertEquals("m1", page.markingNoteBadgeAt(41f, 37f)?.markingId)
        // 行左侧仍然归「点划线 → 笔记弹层」，不能被触控区吃掉。
        assertNull(page.markingNoteBadgeAt(20f, 45f))
        assertNull(page.markingNoteBadgeAt(39f, 60f))
        assertNull(page.markingNoteBadgeAt(60f, 100f))
    }

    @Test
    fun `badge color comes from the host so it can follow the marking style`() {
        val underline = ReaderUnderline(1, 0xFFFF0000.toInt(), 1f, 4f, "")
        val page = page(
            text(0f, 30f, 40f, 60f, chapterPosition = 0, markingId = "m1", style = marked(underline)),
        )

        val badges = ReaderMarkingNoteBadge.createAll(
            page = page,
            notes = mapOf("m1" to "note"),
            // 压深是 Android 侧 ColorUtils 的活（HSV 运算），core/model 只负责把元素交出去。
            badgeColorOf = { 0xFF123456.toInt() },
            density = density,
        )

        assertEquals(0xFF123456.toInt(), badges.single().colorArgb)
    }

    private fun marked(underline: ReaderUnderline) = style.copy(underline = underline)

    private fun text(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        chapterPosition: Int,
        markingId: String?,
        textStyle: ReaderTextStyle = style,
    ) = ReaderElement.Text(
        bounds = ReaderRect(left, top, right, bottom),
        baselinePx = bottom - 4f,
        value = "字",
        style = textStyle,
        selected = false,
        emphasized = false,
        markingId = markingId,
        chapterPosition = chapterPosition,
    )

    private fun page(vararg elements: ReaderElement) = ReaderPage(
        id = ReaderPageId(0, 0),
        chapterTitle = "",
        text = "",
        widthPx = 100,
        heightPx = 100,
        contentTopPx = 0f,
        contentBottomPx = 100f,
        elements = elements.toList(),
        revision = 1L,
        contentRightPx = 100f,
    )
}