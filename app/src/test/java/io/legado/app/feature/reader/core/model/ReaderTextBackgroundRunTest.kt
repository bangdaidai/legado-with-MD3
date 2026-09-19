package io.legado.app.feature.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTextBackgroundRunTest {

    private val image = ReaderTextBackgroundImage("background.png", fit = 3, scale = 1f)
    private val style = ReaderTextStyle(0xFF000000.toInt(), 20f, backgroundImage = image)
    private val plainStyle = ReaderTextStyle(0xFF000000.toInt(), 20f)

    @Test
    fun `merges adjacent text with the same background on one line`() {
        val page = page(
            text(0f, 0f, 10f, 20f, style),
            text(10f, 0f, 25f, 20f, style),
        )

        assertEquals(listOf(ReaderRect(0f, 0f, 25f, 20f)), page.textBackgroundRuns().map { it.contentBounds })
    }

    @Test
    fun `letter spacing gap continues the run when pagination marks it`() {
        val page = page(
            text(0f, 0f, 10f, 20f, style),
            text(12f, 0f, 22f, 20f, style, continues = true),
        )

        assertEquals(
            listOf(ReaderRect(0f, 0f, 22f, 20f)),
            page.textBackgroundRuns().map { it.contentBounds })
    }

    @Test
    fun `unmatched glyph between same image ranges breaks the run`() {
        val page = page(
            text(0f, 0f, 10f, 20f, style),
            text(10f, 0f, 20f, 20f, plainStyle),
            text(20f, 0f, 30f, 20f, style, continues = true),
        )

        assertEquals(2, page.textBackgroundRuns().size)
    }

    @Test
    fun `flagged continuation does not cross rows`() {
        val page = page(
            text(0f, 0f, 10f, 20f, style),
            text(12f, 20f, 22f, 40f, style, continues = true),
        )

        assertEquals(2, page.textBackgroundRuns().size)
    }

    @Test
    fun `does not merge across lines gaps or different images`() {
        val other = style.copy(backgroundImage = image.copy(source = "other.png"))
        val page = page(
            text(0f, 0f, 10f, 20f, style),
            text(12f, 0f, 22f, 20f, style),
            text(0f, 20f, 10f, 40f, style),
            text(10f, 20f, 20f, 40f, other),
        )

        assertEquals(4, page.textBackgroundRuns().size)
    }

    @Test
    fun `nine slice frame no longer expands the run beyond the text`() {
        // 外扩框由绘制期 NinePatchDrawHelper 从文字矩形现算，run 只携带文字矩形。
        val framedStyle = style.copy(
            backgroundImage = image.copy(paddingLeftPx = 3f, paddingRightPx = 4f)
        )
        val page = page(
            text(3f, 0f, 13f, 20f, framedStyle),
            text(13f, 0f, 23f, 20f, framedStyle),
        )

        assertEquals(ReaderRect(3f, 0f, 23f, 20f), page.textBackgroundRuns().single().contentBounds)
    }

    @Test
    fun `bitmap size records source content dimensions`() {
        val resolved = image.withBitmapSize(50, 40)

        assertEquals(50, resolved.sourceWidthPx)
        assertEquals(40, resolved.sourceHeightPx)
    }

    @Test
    fun `raw nine patch border is excluded from the content dimensions`() {
        val resolved = image.copy(source = "background.9.png").withBitmapSize(52, 42)

        assertEquals(50, resolved.sourceWidthPx)
        assertEquals(40, resolved.sourceHeightPx)
    }

    private fun text(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        textStyle: ReaderTextStyle,
        continues: Boolean = false,
    ) = ReaderElement.Text(
        bounds = ReaderRect(left, top, right, bottom),
        baselinePx = bottom - 4f,
        value = "字",
        style = textStyle,
        selected = false,
        emphasized = false,
        chapterPosition = 0,
        continuesBackgroundRun = continues,
    )

    private fun page(vararg elements: ReaderElement) = ReaderPage(
        id = ReaderPageId(0, 0),
        chapterTitle = "chapter",
        text = "",
        widthPx = 100,
        heightPx = 100,
        elements = elements.toList(),
        contentTopPx = 0f,
        contentBottomPx = 100f,
        revision = 1L,
    )
}
