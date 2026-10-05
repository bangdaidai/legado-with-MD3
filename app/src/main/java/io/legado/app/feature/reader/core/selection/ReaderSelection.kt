package io.legado.app.feature.reader.core.selection

import androidx.compose.runtime.Stable
import io.legado.app.feature.reader.core.model.ReaderElement
import io.legado.app.feature.reader.core.model.ReaderPage
import io.legado.app.feature.reader.core.model.ReaderPageWindow
import io.legado.app.feature.reader.core.model.ReaderRect
import java.text.BreakIterator
import java.util.Locale

enum class ReaderSelectionEndpoint {
    ANCHOR,
    FOCUS,
}

/**
 * 选区可跨的页：对照旧 `ContentTextView.upSelectChars`（`relativePage(0..2)`）——当前页、
 * 下一页、下下页，**不含上一页**；滚动模式的连续堆叠允许选到邻章首页（`nextPlusPage` 语义），
 * 分页模式实际只有当前页可见。
 */
fun ReaderPageWindow.selectionPages(): List<ReaderPage> =
    listOfNotNull(current, next, nextPlus)

@Stable
data class ReaderSelection(
    val chapterIndex: Int,
    val anchor: Int,
    val focus: Int,
    val anchorIsTitle: Boolean = false,
    val focusIsTitle: Boolean = anchorIsTitle,
    /**
     * focus 所在的章；[chapterIndex] 始终是 anchor 所在的章。滚动模式的连续堆叠允许选区
     * 跨到邻章页——旧 View 的 `ContentTextView.upSelectChars` 在滚动模式遍历 relativePage
     * 0..2，而 `TextPageFactory.nextPlusPage` 在章末会给出下一章首页，因此那时选区含邻章
     * 首页的文字；分页模式两边恒相等。
     */
    val focusChapterIndex: Int = chapterIndex,
) {
    // Title offsets and body offsets are independent. Document order puts the title first
    // and the body after it; the chapter is the outermost key.
    private val forward: Boolean
        get() = comparePosition(
            chapterIndex, anchorIsTitle, anchor,
            focusChapterIndex, focusIsTitle, focus,
        ) <= 0
    val startChapterIndex: Int get() = if (forward) chapterIndex else focusChapterIndex
    val endChapterIndex: Int get() = if (forward) focusChapterIndex else chapterIndex
    private val startIsTitle: Boolean get() = if (forward) anchorIsTitle else focusIsTitle
    private val endIsTitle: Boolean get() = if (forward) focusIsTitle else anchorIsTitle
    val start: Int get() = if (forward) anchor else focus
    val endInclusive: Int get() = if (forward) focus else anchor
    val includesTitle: Boolean get() = anchorIsTitle || focusIsTitle
    val bodyStart: Int? get() = when {
        anchorIsTitle && focusIsTitle -> null
        includesTitle -> 0
        else -> start
    }

    fun contains(element: ReaderElement.Text, pageChapterIndex: Int): Boolean =
        comparePosition(
            pageChapterIndex, element.emphasized, element.chapterPosition,
            startChapterIndex, startIsTitle, start,
        ) >= 0 && comparePosition(
            pageChapterIndex, element.emphasized, element.chapterPosition,
            endChapterIndex, endIsTitle, endInclusive,
        ) <= 0

    fun moveStart(
        position: Int,
        isTitle: Boolean = false,
        chapter: Int = startChapterIndex,
    ): ReaderSelection =
        if (forward) copy(anchor = position, anchorIsTitle = isTitle, chapterIndex = chapter)
        else copy(focus = position, focusIsTitle = isTitle, focusChapterIndex = chapter)

    fun moveEnd(
        position: Int,
        isTitle: Boolean = false,
        chapter: Int = endChapterIndex,
    ): ReaderSelection =
        if (forward) copy(focus = position, focusIsTitle = isTitle, focusChapterIndex = chapter)
        else copy(anchor = position, anchorIsTitle = isTitle, chapterIndex = chapter)

    fun visualStartEndpoint(): ReaderSelectionEndpoint =
        if (forward) ReaderSelectionEndpoint.ANCHOR else ReaderSelectionEndpoint.FOCUS

    fun visualEndEndpoint(): ReaderSelectionEndpoint =
        if (forward) ReaderSelectionEndpoint.FOCUS else ReaderSelectionEndpoint.ANCHOR

    fun moveEndpoint(
        endpoint: ReaderSelectionEndpoint,
        position: Int,
        isTitle: Boolean = false,
        chapter: Int = when (endpoint) {
            ReaderSelectionEndpoint.ANCHOR -> chapterIndex
            ReaderSelectionEndpoint.FOCUS -> focusChapterIndex
        },
    ): ReaderSelection = when (endpoint) {
        ReaderSelectionEndpoint.ANCHOR ->
            copy(anchor = position, anchorIsTitle = isTitle, chapterIndex = chapter)

        ReaderSelectionEndpoint.FOCUS ->
            copy(focus = position, focusIsTitle = isTitle, focusChapterIndex = chapter)
    }

    fun selectedText(page: ReaderPage): String {
        return selectedText(listOf(page))
    }

    /**
     * Collects a selection across every available page without duplicating page-boundary
     * glyphs. Pages from neighbouring chapters are included when the selection spans them.
     */
    fun selectedText(pages: List<ReaderPage>): String {
        val ordered = pages.asSequence()
            .flatMap { page ->
                page.elements.asSequence()
                    .filterIsInstance<ReaderElement.Text>()
                    .map { page.id.chapterIndex to it }
            }
            .filter { (chapter, text) -> contains(text, chapter) }
            .distinctBy { (chapter, text) ->
                Triple(chapter, text.emphasized, text.chapterPosition) to text.value
            }
            .sortedWith(documentOrderByChapter)
            .toList()
        return buildString {
            var previous: Pair<Int, ReaderElement.Text>? = null
            ordered.forEach { (chapter, text) ->
                previous?.let { (priorChapter, prior) ->
                    if (priorChapter != chapter ||
                        prior.emphasized != text.emphasized ||
                        (prior.paragraphIndex >= 0 && text.paragraphIndex >= 0 &&
                                prior.paragraphIndex != text.paragraphIndex)
                    ) append('\n')
                }
                append(text.value)
                previous = chapter to text
            }
        }
    }

    fun bounds(page: ReaderPage): List<ReaderRect> = page.elements
        .filterIsInstance<ReaderElement.Text>()
        .filter { contains(it, page.id.chapterIndex) }
        .sortedWith(documentOrder)
        .map(ReaderElement.Text::bounds)

    private companion object {
        val documentOrder = compareBy<ReaderElement.Text> { !it.emphasized }.thenBy { it.chapterPosition }
        val documentOrderByChapter = compareBy<Pair<Int, ReaderElement.Text>> { it.first }
            .thenBy { !it.second.emphasized }
            .thenBy { it.second.chapterPosition }

        fun comparePosition(
            leftChapter: Int,
            leftIsTitle: Boolean,
            left: Int,
            rightChapter: Int,
            rightIsTitle: Boolean,
            right: Int,
        ): Int {
            if (leftChapter != rightChapter) return leftChapter.compareTo(rightChapter)
            return if (leftIsTitle == rightIsTitle) left.compareTo(right)
            else if (leftIsTitle) -1 else 1
        }
    }
}

object ReaderSelectionPolicy {
    fun start(page: ReaderPage, x: Float, y: Float): ReaderSelection? =
        (page.elementAt(x, y) as? ReaderElement.Text)?.let {
            ReaderSelection(page.id.chapterIndex, it.chapterPosition, it.chapterPosition, it.emphasized)
        }

    /**
     * Selection handles hang below the text row, so a handle drag often moves through the
     * leading where [ReaderPage.elementAt] misses. Snap a miss to the nearest row by vertical
     * distance, then to the glyph closest to the finger's x within that row.
     */
    fun snapToText(page: ReaderPage, x: Float, y: Float): ReaderElement.Text? {
        (page.elementAt(x, y) as? ReaderElement.Text)?.let { return it }
        val textElements = page.elements.filterIsInstance<ReaderElement.Text>()
        if (textElements.isEmpty()) return null
        fun verticalDistance(bounds: ReaderRect): Float =
            (y - bounds.bottom).coerceAtLeast(0f).coerceAtLeast(bounds.top - y)
        val nearest = textElements.minByOrNull { verticalDistance(it.bounds) } ?: return null
        if (verticalDistance(nearest.bounds) > nearest.bounds.height) return null
        return textElements
            .filter { it.bounds.bottom > nearest.bounds.top && it.bounds.top < nearest.bounds.bottom }
            .minByOrNull { element ->
                val bounds = element.bounds
                when {
                    x < bounds.left -> bounds.left - x
                    x > bounds.right -> x - bounds.right
                    else -> 0f
                }
            }
    }

    /**
     * 长按落点吸附到元素后，按 [unit] 向两侧扩到自然边界。默认 [ReaderSelectionUnit.WORD]
     * 即旧 View 的长按选词行为。
     *
     * 所有粒度都只在本页元素里解析（对照旧的选词实现）：选区端点是章内偏移，跨页的那部分
     * 要等用户拖把手才能选到，粒度本身不替用户决定"要选几页"。
     */
    fun startUnit(
        page: ReaderPage,
        x: Float,
        y: Float,
        unit: ReaderSelectionUnit = ReaderSelectionUnit.WORD,
        locale: Locale = Locale.getDefault(),
    ): ReaderSelection? {
        // Glyph bounds intentionally omit letter- and justification-spacing. Long presses in
        // those visual gaps should start selection just like handle drags do.
        val hit = snapToText(page, x, y) ?: return null
        return when (unit) {
            ReaderSelectionUnit.CHARACTER -> hit.selection(page)
            ReaderSelectionUnit.WORD -> page.breakIteratorSelection(
                hit,
                BreakIterator.getWordInstance(locale),
            )

            ReaderSelectionUnit.SENTENCE -> page.breakIteratorSelection(
                hit,
                BreakIterator.getSentenceInstance(locale),
            )

            ReaderSelectionUnit.LINE -> page.lineSelection(hit)
            ReaderSelectionUnit.PARAGRAPH -> page.paragraphSelection(hit)
        }
    }

    /** Matches the View reader's long-press behavior: select one word in the hit paragraph. */
    fun startWord(
        page: ReaderPage,
        x: Float,
        y: Float,
        locale: Locale = Locale.getDefault(),
    ): ReaderSelection? = startUnit(page, x, y, ReaderSelectionUnit.WORD, locale)

    /**
     * 把命中元素所在的 BreakIterator 区间（词或句）映射回元素区间。BreakIterator 走的是
     * 段落拼接后的字符偏移，因此一个边界可以落在元素中间，此时两端各取整元素。
     */
    private fun ReaderPage.breakIteratorSelection(
        hit: ReaderElement.Text,
        boundary: BreakIterator,
    ): ReaderSelection {
        val paragraph = hit.paragraphElements(this)
        val hitIndex = paragraph.indexOf(hit)
        if (hitIndex < 0) return hit.selection(this)

        val text = paragraph.joinToString(separator = "", transform = ReaderElement.Text::value)
        val hitOffset = paragraph.take(hitIndex).sumOf { it.value.length }
        boundary.setText(text)
        var start = boundary.first()
        var end = boundary.next()
        while (end != BreakIterator.DONE && hitOffset !in start until end) {
            start = end
            end = boundary.next()
        }
        // 落点在末界之后（正常不会发生，尾随空白等异常文本会）：退化成单字，
        // 与旧实现同口径。
        if (end == BreakIterator.DONE) return hit.selection(this)

        var offset = 0
        var first: ReaderElement.Text? = null
        var last: ReaderElement.Text? = null
        paragraph.forEach { element ->
            val elementEnd = offset + element.value.length
            if (offset < end && elementEnd > start) {
                if (first == null) first = element
                last = element
            }
            offset = elementEnd
        }
        return ReaderSelection(
            chapterIndex = id.chapterIndex,
            anchor = first?.chapterPosition ?: hit.chapterPosition,
            focus = last?.chapterPosition ?: hit.chapterPosition,
            anchorIsTitle = hit.emphasized,
        )
    }

    /**
     * 当前视觉行：先按竖向重叠圈出同一行的元素，再在其中按横向连续性切成若干段，取命中
     * 元素所在的那段。分段是为了双栏页面——同一视觉行的左栏与右栏被装订沟隔开，应各算一行。
     * 字形 bounds 不含字距与两端对齐拉伸（见 [startUnit]），行内相邻字形的间隙接近 0，
     * 装订沟则明显大于半行高，因此这个阈值足以把两者分开。
     */
    private fun ReaderPage.lineSelection(hit: ReaderElement.Text): ReaderSelection {
        val band = elements.filterIsInstance<ReaderElement.Text>()
            .filter { it.sharesVisualLineWith(hit) }
            .sortedBy { it.bounds.left }
        val hitIndex = band.indexOf(hit)
        if (hitIndex < 0) return hit.selection(this)
        var start = hitIndex
        while (start > 0 && band.isJoined(start - 1, start)) start--
        var end = hitIndex
        while (end < band.lastIndex && band.isJoined(end, end + 1)) end++
        return band.subList(start, end + 1).toSelection(this, hit)
    }

    /** 命中元素所在的自然段：同一 emphasized、同一 [ReaderElement.Text.paragraphIndex]。 */
    private fun ReaderPage.paragraphSelection(hit: ReaderElement.Text): ReaderSelection {
        val paragraph = hit.paragraphElements(this)
        if (paragraph.size <= 1) return hit.selection(this)
        return ReaderSelection(
            chapterIndex = id.chapterIndex,
            anchor = paragraph.first().chapterPosition,
            focus = paragraph.last().chapterPosition,
            anchorIsTitle = hit.emphasized,
        )
    }

    private fun ReaderElement.Text.selection(page: ReaderPage): ReaderSelection =
        ReaderSelection(
            chapterIndex = page.id.chapterIndex,
            anchor = chapterPosition,
            focus = chapterPosition,
            anchorIsTitle = emphasized,
        )

    /** 与接收者同一段域（同一 emphasized）的元素，按文档序；`paragraphIndex < 0` 时退回整页同域。 */
    private fun ReaderElement.Text.paragraphElements(
        page: ReaderPage,
    ): List<ReaderElement.Text> = page.elements
        .filterIsInstance<ReaderElement.Text>()
        .filter {
            it.emphasized == emphasized &&
                (paragraphIndex < 0 || it.paragraphIndex == paragraphIndex)
        }
        .sortedBy(ReaderElement.Text::chapterPosition)

    /**
     * 是否落在同一条视觉行上。标题与正文偏移各自独立、双栏页面里两侧可能同高，因此还要
     * 同一个 [emphasized] 域，否则会把另一栏或标题的字并进来。
     */
    private fun ReaderElement.Text.sharesVisualLineWith(other: ReaderElement.Text): Boolean {
        if (emphasized != other.emphasized) return false
        val overlap =
            (minOf(bounds.bottom, other.bounds.bottom) - maxOf(bounds.top, other.bounds.top))
                .coerceAtLeast(0f)
        return overlap >= minOf(bounds.height, other.bounds.height) * 0.5f
    }

    private fun lineJoinGap(left: ReaderElement.Text, right: ReaderElement.Text): Float =
        minOf(left.bounds.height, right.bounds.height) * 0.5f

    /** 同一视觉行里 [left] 与 [right] 是否横向连续（未跨过装订沟）。 */
    private fun List<ReaderElement.Text>.isJoined(left: Int, right: Int): Boolean {
        val previous = this[left]
        val next = this[right]
        return next.bounds.left <= previous.bounds.right + lineJoinGap(previous, next)
    }

    private fun List<ReaderElement.Text>.toSelection(
        page: ReaderPage,
        hit: ReaderElement.Text,
    ): ReaderSelection = ReaderSelection(
        chapterIndex = page.id.chapterIndex,
        anchor = minOf(ReaderElement.Text::chapterPosition),
        focus = maxOf(ReaderElement.Text::chapterPosition),
        anchorIsTitle = hit.emphasized,
    )

    /**
     * [allowChapterCrossing] 只在滚动模式传 true：视口里堆叠的就是当前页与下一章首页
     * （旧 View 同样把这一页纳入选区分词，见 [ReaderSelection.focusChapterIndex]）。
     * 分页模式保持单章，与旧 View 的 `last = if (isScroll) 2 else 0` 一致。
     */
    fun extend(
        selection: ReaderSelection,
        page: ReaderPage,
        x: Float,
        y: Float,
        allowChapterCrossing: Boolean = false,
    ): ReaderSelection {
        if (!allowChapterCrossing && selection.chapterIndex != page.id.chapterIndex) return selection
        return (page.elementAt(x, y) as? ReaderElement.Text)?.let {
            selection.copy(
                focus = it.chapterPosition,
                focusIsTitle = it.emphasized,
                focusChapterIndex = page.id.chapterIndex,
            )
        } ?: selection
    }
}
