package io.legado.app.feature.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 笔记角标的落位与命中。这里钉的是三件事：只给**有备注**的划线出角标、角标跟到划线的
 * **末行**、绘制矩形与命中矩形是同一份几何（命中区只是外扩）。
 */
class ReaderMarkingNoteBadgeTest {
    private val density = 2f
    private val style = ReaderTextStyle(0xFF000000.toInt(), 20f)
    private val marked = style.copy(underline = ReaderUnderline(1, 0xFFFF0000.toInt(), 1f, 2f, ""))

    private fun badges(
        vararg elements: ReaderElement,
        notes: Map<String, String>,
        contentRightPx: Float = 100f,
        scrollExtentPx: Float = 100f,
    ) = ReaderMarkingNoteBadge.createAll(
        page = page(
            *elements,
            contentRightPx = contentRightPx,
            scrollExtentPx = scrollExtentPx,
        ),
        notes = notes,
        accentColorArgb = 0xFF00FF00.toInt(),
        density = density,
    )

    @Test
    fun `markings without a note get no badge`() {
        val element = text(0f, 0f, 20f, 20f, chapterPosition = 0, markingId = "m1")
        assertTrue(badges(element, notes = emptyMap()).isEmpty())
        // 备注存在但为空串：同样没有可看的备注，不出角标。
        assertTrue(badges(element, notes = mapOf("m1" to "  ")).isEmpty())
        // 快照里有这条备注，但本页没有对应划线（备注在别的页）。
        assertTrue(badges(notes = mapOf("m1" to "note")).isEmpty())
    }

    @Test
    fun `badge follows the last line of the marking`() {
        val badges = badges(
            text(0f, 0f, 20f, 20f, chapterPosition = 0, markingId = "m1"),
            text(0f, 20f, 40f, 40f, chapterPosition = 5, markingId = "m1"),
            notes = mapOf("m1" to "note"),
        )

        assertEquals(1, badges.size)
        // 9dp 直径 @2x = 18px，落在末行（bottom 40）右下角，右侧留 1.5dp（6px）空档。
        assertEquals(ReaderRect(25f, 22f, 43f, 40f), badges.single().bounds)
    }

    @Test
    fun `badge is clamped to the content edge when the marking fills the line`() {
        val badges = badges(
            text(0f, 0f, 100f, 20f, chapterPosition = 0, markingId = "m1"),
            notes = mapOf("m1" to "note"),
            contentRightPx = 100f,
        )

        assertEquals(ReaderRect(82f, 2f, 100f, 20f), badges.single().bounds)
    }

    @Test
    fun `badge is not pinned to the viewport bottom in continuous scroll`() {
        // 连续卷动页是整章：heightPx/contentBottomPx 只是一屏，正文一直排到 scrollExtentPx。
        // 收口必须对页自身范围，否则第一屏以下的角标全被钉在视口底边（同一个角标画在所有屏上）。
        val badges = badges(
            text(0f, 480f, 100f, 500f, chapterPosition = 0, markingId = "m1"),
            notes = mapOf("m1" to "note"),
            scrollExtentPx = 600f,
        )

        assertEquals(ReaderRect(82f, 482f, 100f, 500f), badges.single().bounds)
    }

    @Test
    fun `one badge per marking and the hit box is the draw box widened`() {
        val badges = badges(
            text(0f, 0f, 20f, 20f, chapterPosition = 0, markingId = "m1"),
            text(0f, 0f, 20f, 20f, chapterPosition = 0, markingId = "m2"),
            notes = mapOf("m1" to "a", "m2" to "b"),
        )

        assertEquals(listOf("m1", "m2"), badges.map { it.markingId })
        val badge = badges.first()
        // 命中区至少 24dp 见方（@2x = 48px），以角标中心外扩，且不越出页。
        assertEquals(48f, badge.hitBounds.width)
        assertEquals(48f, badge.hitBounds.height)
        assertTrue(badge.hitBounds.left >= 0f)
        assertTrue(badge.hitBounds.right <= 100f)
    }

    @Test
    fun `hit test only fires inside the hit box`() {
        val source = page(
            text(40f, 0f, 60f, 20f, chapterPosition = 0, markingId = "m1"),
        )
        val page = source.copy(
            decoration = ReaderPageDecoration(
                markingNoteBadges = ReaderMarkingNoteBadge.createAll(
                    page = source,
                    notes = mapOf("m1" to "note"),
                    accentColorArgb = 0xFF00FF00.toInt(),
                    density = density,
                ),
            ),
        )

        // 命中区 = 角标中心外扩到 48px 见方（(30,0)-(78,48)），比绘制矩形大得多，
        // 但也没有大到吞掉整行：行左侧仍然归「点划线 → 笔记弹层」。
        assertEquals("m1", page.markingNoteBadgeAt(54f, 11f)?.markingId)
        assertEquals("m1", page.markingNoteBadgeAt(31f, 2f)?.markingId)
        assertNull(page.markingNoteBadgeAt(10f, 10f))
        assertNull(page.markingNoteBadgeAt(54f, 60f))
    }

    @Test
    fun `badge color follows the marking style`() {
        val badges = badges(
            text(0f, 0f, 20f, 20f, chapterPosition = 0, markingId = "m1", style = marked),
            notes = mapOf("m1" to "note"),
        )
        assertEquals(0xFFFF0000.toInt(), badges.single().colorArgb)

        val fallback = badges(
            text(0f, 0f, 20f, 20f, chapterPosition = 0, markingId = "m1"),
            notes = mapOf("m1" to "note"),
        )
        // 划线没配线色也没配背景色（纯字体色笔记）时回落到强调色。
        assertEquals(0xFF00FF00.toInt(), fallback.single().colorArgb)
    }

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

    private fun page(
        vararg elements: ReaderElement,
        contentRightPx: Float = 100f,
        scrollExtentPx: Float = 100f,
    ) = ReaderPage(
        id = ReaderPageId(0, 0),
        chapterTitle = "",
        text = "",
        widthPx = 100,
        heightPx = 100,
        contentTopPx = 0f,
        contentBottomPx = 100f,
        elements = elements.toList(),
        revision = 1L,
        scrollExtentPx = scrollExtentPx,
        contentRightPx = contentRightPx,
    )
}