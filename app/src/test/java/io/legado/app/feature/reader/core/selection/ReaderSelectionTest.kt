package io.legado.app.feature.reader.core.selection

import io.legado.app.feature.reader.core.model.ReaderElement
import io.legado.app.feature.reader.core.model.ReaderPage
import io.legado.app.feature.reader.core.model.ReaderPageId
import io.legado.app.feature.reader.core.model.ReaderRect
import io.legado.app.feature.reader.core.model.ReaderTextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ReaderSelectionTest {
    private val style = ReaderTextStyle(0, 16f)
    private val page = ReaderPage(
        ReaderPageId(0, 0), "", "甲乙丙", 100, 100, 0f, 20f,
        listOf(
            ReaderElement.Text(ReaderRect(0f, 0f, 10f, 20f), 15f, "甲", style, false, false, chapterPosition = 0),
            ReaderElement.Text(ReaderRect(10f, 0f, 20f, 20f), 15f, "乙", style, false, false, chapterPosition = 1),
            ReaderElement.Text(ReaderRect(20f, 0f, 30f, 20f), 15f, "丙", style, false, false, chapterPosition = 2),
        ), 1L,
    )

    /**
     * 每个 [ReaderElement.Text] 占 10px 宽、一行高。元素粒度由排版层决定，这里由测试
     * 自己构造，因此断言只保证“一个元素作为整体被选中/跳过”，不保证排版层如何分词。
     */
    private fun textPage(values: List<String>): ReaderPage {
        var position = 0
        val elements = values.mapIndexed { index, value ->
            ReaderElement.Text(
                ReaderRect(index * 10f, 0f, index * 10f + 10f, 20f),
                15f,
                value,
                style,
                selected = false,
                emphasized = false,
                chapterPosition = position.also { position += value.length },
                paragraphIndex = 0,
            )
        }
        return page.copy(text = values.joinToString(""), elements = elements)
    }

    @Test fun reverseSelectionNormalizesAndPreservesTextOrder() {
        val selection = ReaderSelection(0, 2, 0)
        assertEquals(0, selection.start)
        assertEquals(2, selection.endInclusive)
        assertEquals("甲乙丙", selection.selectedText(page))
    }

    @Test fun hitTestStartsAndExtendsSelection() {
        val started = ReaderSelectionPolicy.start(page, 5f, 10f)!!
        val extended = ReaderSelectionPolicy.extend(started, page, 25f, 10f)
        assertEquals("甲乙丙", extended.selectedText(page))
    }

    @Test fun snapToTextKeepsDirectHits() {
        assertEquals(1, ReaderSelectionPolicy.snapToText(page, 15f, 10f)!!.chapterPosition)
    }

    @Test fun snapToTextSnapsHandleDragsBelowTheRowToTheNearestGlyph() {
        assertEquals(0, ReaderSelectionPolicy.snapToText(page, 5f, 28f)!!.chapterPosition)
    }

    @Test fun snapToTextSnapsPastTheRowEdgeToTheNearestGlyph() {
        assertEquals(2, ReaderSelectionPolicy.snapToText(page, 35f, 28f)!!.chapterPosition)
    }

    @Test fun snapToTextIgnoresTouchesFarFromAnyText() {
        assertNull(ReaderSelectionPolicy.snapToText(page, 5f, 60f))
    }

    @Test fun longPressSelectsTheWholeWordAcrossVisualLines() {
        val values = listOf("read", "er", " ", "canvas")
        var position = 0
        val elements = values.mapIndexed { index, value ->
            ReaderElement.Text(
                bounds = ReaderRect(
                    if (index == 1) 0f else index * 20f,
                    if (index == 1) 20f else 0f,
                    if (index == 1) 20f else index * 20f + 20f,
                    if (index == 1) 40f else 20f,
                ),
                baselinePx = if (index == 1) 35f else 15f,
                value = value,
                style = style,
                selected = false,
                emphasized = false,
                chapterPosition = position.also { position += value.length },
                paragraphIndex = 0,
            )
        }
        val wrapped = page.copy(text = values.joinToString(""), elements = elements)

        val selection = ReaderSelectionPolicy.startWord(wrapped, 5f, 30f, Locale.ENGLISH)!!

        assertEquals("reader", selection.selectedText(wrapped))
        assertEquals(0, selection.start)
        assertEquals(4, selection.endInclusive)
    }

    @Test fun longPressKeepsWordSelectionInsideTheHitParagraph() {
        val first = page.elements.filterIsInstance<ReaderElement.Text>().map {
            it.copy(paragraphIndex = 0)
        }
        val second = listOf(
            ReaderElement.Text(
                ReaderRect(0f, 30f, 20f, 50f), 45f, "word", style, false, false,
                chapterPosition = 4, paragraphIndex = 1,
            ),
        )
        val paragraphs = page.copy(elements = first + second)

        val selection = ReaderSelectionPolicy.startWord(paragraphs, 5f, 40f, Locale.ENGLISH)!!

        assertEquals("word", selection.selectedText(paragraphs))
    }

    @Test fun intentionalLatinDragReturnsToElementGranularity() {
        val latin = textPage("one two three".map { it.toString() })
        val initial = ReaderSelectionPolicy.startWord(latin, 5f, 10f, Locale.ENGLISH)!!

        val expandedInsideWord = ReaderSelectionPolicy.extend(initial, latin, 105f, 10f)
        val contractedInsideWord = ReaderSelectionPolicy.extend(
            ReaderSelection(0, 0, 12), latin, 95f, 10f,
        )

        assertEquals("one", initial.selectedText(latin))
        assertEquals("one two thr", expandedInsideWord.selectedText(latin))
        assertEquals("one two th", contractedInsideWord.selectedText(latin))
    }

    @Test fun reversedDragRemainsElementGranular() {
        val latin = textPage("one two three".map { it.toString() })
        val reversed = ReaderSelection(0, 12, 0)

        val moved = ReaderSelectionPolicy.extend(reversed, latin, 15f, 10f)

        assertEquals(12, moved.anchor)
        assertEquals(1, moved.focus)
        assertEquals("ne two three", moved.selectedText(latin))
    }

    @Test fun bothSemanticEndpointsCanMoveInsideLatinWords() {
        val latin = textPage("one two three".map { it.toString() })
        val selection = ReaderSelection(0, 0, 12)

        val anchorMoved = selection.moveEndpoint(ReaderSelectionEndpoint.ANCHOR, 1)
        val focusMoved = selection.moveEndpoint(ReaderSelectionEndpoint.FOCUS, 9)

        assertEquals("ne two three", anchorMoved.selectedText(latin))
        assertEquals("one two th", focusMoved.selectedText(latin))
    }

    @Test fun eitherEndpointCanCrossWithoutChangingItsIdentity() {
        val anchorCrossed = ReaderSelection(0, 0, 6)
            .moveEndpoint(ReaderSelectionEndpoint.ANCHOR, 9)
        val focusCrossed = ReaderSelection(0, 6, 12)
            .moveEndpoint(ReaderSelectionEndpoint.FOCUS, 5)

        assertEquals(9, anchorCrossed.anchor)
        assertEquals(6, anchorCrossed.focus)
        assertEquals(6, focusCrossed.anchor)
        assertEquals(5, focusCrossed.focus)
    }

    @Test fun cjkDragKeepsExistingElementGranularity() {
        val selection = ReaderSelection(0, 0, 2)

        val contracted = ReaderSelectionPolicy.extend(selection, page, 15f, 10f)

        assertEquals("甲乙", contracted.selectedText(page))
        assertEquals(1, contracted.focus)
    }

    @Test fun apostrophesRemainOrdinaryReaderElementsDuringDrag() {
        listOf("'", "’").forEach { apostrophe ->
            val latin = textPage(listOf("d", "o", "n", apostrophe, "t"))
            val selection = ReaderSelection(0, 0, 4)

            val contracted = ReaderSelectionPolicy.extend(selection, latin, 35f, 10f)

            assertEquals("don$apostrophe", contracted.selectedText(latin))
            assertEquals(3, contracted.focus)
        }
    }

    @Test fun dragKeepsAMultiCodeUnitElementWhole() {
        val latin = textPage(listOf("c", "a", "f", "e\u0301", "x"))
        val selection = ReaderSelection(0, 0, 6)

        val contracted = ReaderSelectionPolicy.extend(selection, latin, 35f, 10f)

        assertEquals(3, contracted.focus)
        assertEquals("cafe\u0301", contracted.selectedText(latin))
    }

    @Test
    fun longPressSnapsAcrossLetterSpacingGaps() {
        val spaced = page.copy(
            elements = listOf(
                ReaderElement.Text(
                    ReaderRect(0f, 0f, 10f, 20f), 15f, "甲", style, false, false,
                    chapterPosition = 0,
                ),
                ReaderElement.Text(
                    ReaderRect(14f, 0f, 24f, 20f), 15f, "乙", style, false, false,
                    chapterPosition = 1,
                ),
            )
        )

        val selection = ReaderSelectionPolicy.startWord(spaced, 12f, 10f, Locale.CHINESE)

        assertEquals(0, selection?.anchor)
        assertEquals("甲乙", selection?.selectedText(spaced))
    }

    @Test fun handlesKeepSemanticStartWhenSelectionIsReversed() {
        val reversed = ReaderSelection(0, 2, 0)
        assertEquals(1, reversed.moveStart(1).start)
        assertEquals(1, reversed.moveEnd(1).endInclusive)
    }

    @Test fun draggedEndpointIdentityStaysStableAfterHandlesCross() {
        val selection = ReaderSelection(0, 0, 2)
        val draggedEndpoint = selection.visualStartEndpoint()

        val crossed = selection.moveEndpoint(draggedEndpoint, 3)
        val continued = crossed.moveEndpoint(draggedEndpoint, 4)

        assertEquals(2, continued.start)
        assertEquals(4, continued.endInclusive)
        assertEquals(4, continued.anchor)
        assertEquals(2, continued.focus)
    }

    private val titledPage = page.copy(elements = listOf(
        ReaderElement.Text(ReaderRect(0f, 30f, 10f, 50f), 45f, "题", style, false, true, chapterPosition = 0),
    ) + page.elements)

    @Test fun bodySelectionDoesNotIncludeTitleWithTheSameOffset() {
        val selection = ReaderSelectionPolicy.start(titledPage, 5f, 10f)!!
        assertEquals("甲", selection.selectedText(titledPage))
        assertEquals(1, selection.bounds(titledPage).size)
    }

    @Test fun titleSelectionDoesNotIncludeBodyWithTheSameOffset() {
        val selection = ReaderSelectionPolicy.start(titledPage, 5f, 40f)!!
        assertEquals("题", selection.selectedText(titledPage))
        assertEquals(1, selection.bounds(titledPage).size)
    }

    @Test fun crossTitleAndBodySelectionPreservesDocumentOrderInBothDirections() {
        val title = ReaderSelectionPolicy.start(titledPage, 5f, 40f)!!
        val forward = ReaderSelectionPolicy.extend(title, titledPage, 15f, 10f)
        val body = ReaderSelectionPolicy.start(titledPage, 15f, 10f)!!
        val backward = ReaderSelectionPolicy.extend(body, titledPage, 5f, 40f)
        assertEquals("题\n甲乙", forward.selectedText(titledPage))
        assertEquals(forward.selectedText(titledPage), backward.selectedText(titledPage))
    }

    @Test fun draggingIntoAnotherChapterDoesNotChangeSelection() {
        val selection = ReaderSelection(0, 0, 0)
        val otherChapter = page.copy(id = ReaderPageId(1, 0))
        assertEquals(selection, ReaderSelectionPolicy.extend(selection, otherChapter, 25f, 10f))
    }

    @Test fun handlesCanCrossTheTitleBodyBoundaryWithoutConfusingOffsets() {
        val title = ReaderSelectionPolicy.start(titledPage, 5f, 40f)!!
        assertNull(title.bodyStart)
        val forward = title.moveEnd(1)
        assertEquals("题\n甲乙", forward.selectedText(titledPage))
        assertEquals(0, forward.bodyStart)
        assertEquals("甲乙", forward.moveStart(0).selectedText(titledPage))
        val backward = ReaderSelection(0, 1, 0, focusIsTitle = true)
        assertEquals("甲乙", backward.moveStart(0).selectedText(titledPage))
        assertEquals("题", backward.moveEnd(0, isTitle = true).selectedText(titledPage))
    }

    @Test fun selectionBoundsFollowDocumentOrderInsteadOfPhysicalColumnHeight() {
        val shuffled = page.copy(elements = page.elements.reversed())
        val selection = ReaderSelection(0, 2, 0)
        assertEquals("甲乙丙", selection.selectedText(shuffled))
        assertEquals(page.elements.map { it.bounds }, selection.bounds(shuffled))
    }

    @Test fun copyingPreservesParagraphBreaksButNotVisualLineWraps() {
        val elements = page.elements.filterIsInstance<ReaderElement.Text>().mapIndexed { index, text ->
            text.copy(
                paragraphIndex = if (index < 2) 0 else 1,
                chapterPosition = if (index < 2) index else 3,
                bounds = ReaderRect(0f, index * 20f, 10f, (index + 1) * 20f),
            )
        }
        val paragraphs = page.copy(elements = elements)
        assertEquals("甲乙\n丙", ReaderSelection(0, 0, 3).selectedText(paragraphs))
        assertEquals("乙\n丙", ReaderSelection(0, 3, 1).selectedText(paragraphs))
        assertEquals("甲乙", ReaderSelection(0, 0, 1).selectedText(paragraphs))
    }

    @Test fun copyingAcrossPagesUsesDocumentOrderAndRemovesBoundaryDuplicates() {
        val first = page.copy(
            elements = page.elements.take(2),
            id = ReaderPageId(0, 0),
        )
        val second = page.copy(
            elements = listOf(
                page.elements[1],
                page.elements[2],
            ),
            id = ReaderPageId(0, 1),
        )

        assertEquals("甲乙丙", ReaderSelection(0, 0, 2).selectedText(listOf(second, first)))
    }

    /**
     * 滚动模式视口里堆叠的下邻页属于下一章（旧 View 的选区分词同样遍历 relativePage 0..2，
     * 而 `TextPageFactory.nextPlusPage` 在章末给出下一章首页），因此选区必须能跨过去，
     * 且两页都要参与高亮绘制。
     */
    @Test
    fun scrollModeSelectionExtendsIntoTheNextChapterPage() {
        val nextChapter = page.copy(
            id = ReaderPageId(1, 0),
            elements = listOf(
                ReaderElement.Text(
                    ReaderRect(0f, 0f, 10f, 20f), 15f, "丁", style, false, false,
                    chapterPosition = 0,
                ),
            ),
        )
        val started = ReaderSelectionPolicy.start(page, 25f, 10f)!!
        val extended = ReaderSelectionPolicy.extend(
            started, nextChapter, 5f, 10f, allowChapterCrossing = true,
        )

        assertEquals(0, extended.startChapterIndex)
        assertEquals(1, extended.endChapterIndex)
        assertEquals(1, extended.focusChapterIndex)
        assertEquals("丙\n丁", extended.selectedText(listOf(nextChapter, page)))
        assertEquals(1, extended.bounds(page).size)
        assertEquals(1, extended.bounds(nextChapter).size)
    }

    @Test
    fun pagedModeKeepsTheSelectionInsideOneChapter() {
        val nextChapter = page.copy(id = ReaderPageId(1, 0))
        val started = ReaderSelectionPolicy.start(page, 25f, 10f)!!

        assertEquals(started, ReaderSelectionPolicy.extend(started, nextChapter, 5f, 10f))
        assertEquals(0, started.endChapterIndex)
    }

    /** 每个元素一个字符、10px 宽、20px 高；按 [row] 排行、按 [offset] 排行内起点。 */
    private fun gridPage(
        text: String,
        row: (Int) -> Int,
        offset: (Int) -> Float,
        paragraph: (Int) -> Int = { 0 },
        widthPx: Int = 100,
    ): ReaderPage {
        val elements = text.mapIndexed { index, value ->
            val line = row(index)
            ReaderElement.Text(
                ReaderRect(
                    offset(index),
                    line * 20f,
                    offset(index) + 10f,
                    line * 20f + 20f,
                ),
                line * 20f + 15f,
                value.toString(),
                style,
                selected = false,
                emphasized = false,
                chapterPosition = index,
                paragraphIndex = paragraph(index),
            )
        }
        return page.copy(widthPx = widthPx, text = text, elements = elements)
    }

    /** 长按按字只取落点那一个元素；按句取到句末标点，两句各选各的。 */
    @Test
    fun longPressSelectsByCharacterOrSentence() {
        val sentences = gridPage("甲乙。丙丁。", row = { 0 }, offset = { it * 10f })

        val byCharacter = ReaderSelectionPolicy.startUnit(
            sentences, 15f, 10f, ReaderSelectionUnit.CHARACTER,
        )!!
        val bySentence = ReaderSelectionPolicy.startUnit(
            sentences, 5f, 10f, ReaderSelectionUnit.SENTENCE, Locale.CHINESE,
        )!!
        val nextSentence = ReaderSelectionPolicy.startUnit(
            sentences, 35f, 10f, ReaderSelectionUnit.SENTENCE, Locale.CHINESE,
        )!!

        assertEquals("乙", byCharacter.selectedText(sentences))
        assertEquals("甲乙。", bySentence.selectedText(sentences))
        assertEquals("丙丁。", nextSentence.selectedText(sentences))
    }

    /**
     * 按句在拉丁文上同样生效。**不断言精确边界**：JDK CLDR 与 Android ICU 对"句末标点
     * 之后的空格归上一句还是下一句"的处理不同，这里只锁定"不吞掉相邻句"。
     */
    @Test
    fun longPressBySentenceStopsAtLatinTerminators() {
        val latin = gridPage("one. two. three!", row = { 0 }, offset = { it * 10f })

        val selection = ReaderSelectionPolicy.startUnit(
            latin, 45f, 10f, ReaderSelectionUnit.SENTENCE, Locale.ENGLISH,
        )!!
        val text = selection.selectedText(latin)

        assertTrue(text.contains("two."))
        assertFalse(text.contains("one."))
        assertFalse(text.contains("three"))
    }

    /** 按行：竖向同带 + 横向连续的那一段，跨行不带。 */
    @Test
    fun longPressSelectsTheWholeVisualLine() {
        val rows = gridPage("甲乙丙丁戊己", row = { it / 3 }, offset = { (it % 3) * 10f })

        val firstRow = ReaderSelectionPolicy.startUnit(rows, 25f, 10f, ReaderSelectionUnit.LINE)!!
        val secondRow = ReaderSelectionPolicy.startUnit(rows, 5f, 30f, ReaderSelectionUnit.LINE)!!

        assertEquals("甲乙丙", firstRow.selectedText(rows))
        assertEquals("丁戊己", secondRow.selectedText(rows))
    }

    /**
     * 双栏页面：同一视觉行的左右两栏被装订沟隔开，各算一行。长按左栏不应把右栏的字并进来
     * （否则"按行"会一次选中两栏）。
     */
    @Test
    fun longPressByLineStopsAtTheColumnGutter() {
        val columns = gridPage(
            text = "甲乙丙丁戊己庚辛",
            row = { 0 },
            offset = { index -> if (index < 4) index * 10f else 100f + (index - 4) * 10f },
            paragraph = { index -> if (index < 4) 0 else 1 },
            widthPx = 200,
        )

        val left = ReaderSelectionPolicy.startUnit(columns, 5f, 10f, ReaderSelectionUnit.LINE)!!
        val right = ReaderSelectionPolicy.startUnit(columns, 105f, 10f, ReaderSelectionUnit.LINE)!!

        assertEquals("甲乙丙丁", left.selectedText(columns))
        assertEquals("戊己庚辛", right.selectedText(columns))
    }

    /** 按段：命中元素所在的自然段整段选中，不越到相邻段。 */
    @Test
    fun longPressSelectsTheWholeParagraph() {
        val paragraphs = gridPage(
            text = "甲乙丙丁戊",
            row = { it / 3 },
            offset = { (it % 3) * 10f },
            paragraph = { index -> if (index < 3) 0 else 1 },
        )

        val first = ReaderSelectionPolicy.startUnit(
            paragraphs, 5f, 10f, ReaderSelectionUnit.PARAGRAPH,
        )!!
        val second = ReaderSelectionPolicy.startUnit(
            paragraphs, 5f, 30f, ReaderSelectionUnit.PARAGRAPH,
        )!!

        assertEquals("甲乙丙", first.selectedText(paragraphs))
        assertEquals("丁戊", second.selectedText(paragraphs))
    }

    /** 粒度只决定初始选区：扩完之后两端把手仍可任意拖动。 */
    @Test
    fun autoSelectedRangeStaysFullyDraggable() {
        val oneLine = gridPage("甲乙丙", row = { 0 }, offset = { it * 10f })
        val auto = ReaderSelectionPolicy.startUnit(oneLine, 5f, 10f, ReaderSelectionUnit.LINE)!!
        val start = auto.visualStartEndpoint()
        val end = auto.visualEndEndpoint()

        assertEquals("甲乙丙", auto.selectedText(oneLine))
        assertEquals("乙丙", auto.moveEndpoint(start, 1).selectedText(oneLine))
        assertEquals("丙", auto.moveEndpoint(start, 2).selectedText(oneLine))
        assertEquals("甲乙", auto.moveEndpoint(end, 1).selectedText(oneLine))
    }

    @Test
    fun preferenceFallsBackToWordForUnknownValues() {
        assertEquals(ReaderSelectionUnit.CHARACTER, ReaderSelectionUnit.fromPreference("0"))
        assertEquals(ReaderSelectionUnit.WORD, ReaderSelectionUnit.fromPreference("1"))
        assertEquals(ReaderSelectionUnit.SENTENCE, ReaderSelectionUnit.fromPreference("2"))
        assertEquals(ReaderSelectionUnit.LINE, ReaderSelectionUnit.fromPreference("3"))
        assertEquals(ReaderSelectionUnit.PARAGRAPH, ReaderSelectionUnit.fromPreference("4"))
        assertEquals(ReaderSelectionUnit.WORD, ReaderSelectionUnit.fromPreference(""))
        assertEquals(ReaderSelectionUnit.WORD, ReaderSelectionUnit.fromPreference("9"))
    }
}
