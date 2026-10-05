package io.legado.app.feature.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 笔记角标的落位与命中。落位口径：**贴末字右侧 1dp**、**与下划线居中对齐**、**不收口**
 * （可以探出页边），命中区是绘制矩形外扩到 24dp。
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
    fun `badge follows the last line and sits one dp right of the last glyph`() {
        val badges = badges(
            text(0f, 0f, 20f, 30f, chapterPosition = 0, markingId = "m1"),
            text(0f, 30f, 40f, 60f, chapterPosition = 5, markingId = "m1"),
            notes = mapOf("m1" to "note"),
        )

        // 12dp 直径 @2x = 24px；末字 right=40，右侧留 1dp（2px）。
        // 没有下划线 → 与行盒竖直中心（(30+60)/2 = 45）对齐。
        assertEquals(1, badges.size)
        assertEquals(ReaderRect(42f, 33f, 66f, 57f), badges.single().bounds)
    }

    @Test
    fun `badge is centered on the underline instead of the line box`() {
        val underline = ReaderUnderline(1, 0xFFFF0000.toInt(), 1f, offsetPx = 4f)
        val badges = badges(
            text(0f, 30f, 40f, 60f, chapterPosition = 0, markingId = "m1", style = marked(underline)),
            notes = mapOf("m1" to "note"),
        )

        // 下划线画在行盒下沿 60 + 偏移 4 = 64 处，角标中心就该在 64（而不是行盒中心 45）。
        val bounds = badges.single().bounds
        assertEquals(64f, bounds.top + bounds.height / 2f)
        assertEquals(ReaderRect(42f, 52f, 66f, 76f), bounds)
    }

    @Test
    fun `badge is not clamped to the page edge`() {
        val underline = ReaderUnderline(1, 0xFFFF0000.toInt(), 1f, offsetPx = 4f)
        val badges = badges(
            text(0f, 0f, 100f, 30f, chapterPosition = 0, markingId = "m1", style = marked(underline)),
            notes = mapOf("m1" to "note"),
        )

        // 页宽只有 100，角标探出页边 26px：收口会正好压在末字上，那是不该收的地方。
        assertEquals(ReaderRect(102f, 22f, 126f, 46f), badges.single().bounds)
    }

    @Test
    fun `one badge per marking and the hit box grows mostly into the page margin`() {
        val badges = badges(
            text(0f, 0f, 20f, 30f, chapterPosition = 0, markingId = "m1"),
            text(0f, 0f, 20f, 30f, chapterPosition = 0, markingId = "m2"),
            notes = mapOf("m1" to "a", "m2" to "b"),
        )

        assertEquals(listOf("m1", "m2"), badges.map { it.markingId })
        // 角标矩形 (22,3)-(46,27)；命中区竖向 48px（24dp），横向只左长 4dp（8px）到 14，
        // 其余全往右长到 62：末字在右边，对称外扩会把「点原文」的手感一起吃掉。
        assertEquals(ReaderRect(14f, -9f, 62f, 39f), badges.first().hitBounds)
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

        // 角标矩形 (62,52)-(86,76)，命中区 (54,40)-(102,88)。
        assertEquals("m1", page.markingNoteBadgeAt(74f, 64f)?.markingId)
        assertEquals("m1", page.markingNoteBadgeAt(55f, 41f)?.markingId)
        // 行左侧仍然归「点划线 → 笔记弹层」，不能被角标的命中区吃掉。
        assertNull(page.markingNoteBadgeAt(20f, 45f))
        assertNull(page.markingNoteBadgeAt(74f, 100f))
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