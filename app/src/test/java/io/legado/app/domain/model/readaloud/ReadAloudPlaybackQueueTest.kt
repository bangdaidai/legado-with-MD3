package io.legado.app.domain.model.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadAloudPlaybackQueueTest {

    private val queue = ReadAloudPlaybackQueue.from(
        listOf(
            item("旁白", 10, 0),
            item("“你好”", 12, 0, SpeechRoleType.Character),
            item("回答。", 30, 1),
        )
    )

    @Test
    fun `finds cue and offset by absolute chapter position`() {
        assertEquals(ReadAloudPlaybackCursor(0, 1), queue.cursorAt(11))
        assertEquals(ReadAloudPlaybackCursor(1, 2), queue.cursorAt(14))
    }

    @Test
    fun `moves gaps to next cue and clamps after chapter content`() {
        assertEquals(ReadAloudPlaybackCursor(2, 0), queue.cursorAt(20))
        assertEquals(ReadAloudPlaybackCursor(2, 3), queue.cursorAt(100))
    }

    @Test
    fun `navigates without paragraph assumptions`() {
        val middle = ReadAloudPlaybackCursor(1, 2)
        assertEquals(ReadAloudPlaybackCursor(0, 0), queue.previous(middle))
        assertEquals(ReadAloudPlaybackCursor(2, 0), queue.next(middle))
        assertNull(queue.previous(ReadAloudPlaybackCursor(0, 0)))
        assertNull(queue.next(ReadAloudPlaybackCursor(2, 0)))
    }

    @Test
    fun `chapter title is first playback cue but excluded from position lookup`() {
        val titledQueue = queue.withChapterTitle(" 第 1 章 ")

        assertEquals("第 1 章", titledQueue.cues.first().text)
        assertEquals(true, titledQueue.cues.first().isChapterTitle)
        assertEquals(1, titledQueue.leadingTitleCueCount)
        assertEquals(ReadAloudPlaybackCursor(1, 0), titledQueue.cursorAt(10))
        assertEquals(
            ReadAloudPlaybackCursor(1, 0),
            titledQueue.next(ReadAloudPlaybackCursor(0, 0)),
        )
    }

    @Test
    fun `empty playback queue does not become title-only queue`() {
        assertEquals(true, ReadAloudPlaybackQueue.Empty.withChapterTitle("标题").isEmpty)
    }

    @Test
    fun `merges punctuation-only cue into previous cue`() {
        val merged = ReadAloudPlaybackQueue.from(
            listOf(
                item("你好", 0),
                item("。", 2),
                item("再见", 3),
            )
        )

        assertEquals(2, merged.cues.size)
        assertEquals("你好。", merged.cues.first().text)
        assertEquals(3, merged.cues.first().chapterEnd)
        assertEquals(ReadAloudPlaybackCursor(1, 0), merged.cursorAt(3))
    }

    @Test
    fun `folds consecutive silent cues into one backward merge`() {
        val merged = ReadAloudPlaybackQueue.from(
            listOf(
                item("你好", 0),
                item("”", 2),
                item("。", 3),
                item("再见", 4),
            )
        )

        assertEquals(2, merged.cues.size)
        assertEquals("你好”。", merged.cues.first().text)
    }

    @Test
    fun `prepends silent cue to next cue when previous is not adjacent`() {
        val merged = ReadAloudPlaybackQueue.from(
            listOf(
                item("你好", 0),
                item("……", 4),
                item("再见", 6),
            )
        )

        assertEquals(2, merged.cues.size)
        assertEquals("……再见", merged.cues.last().text)
        assertEquals(4, merged.cues.last().chapterStart)
        assertEquals(ReadAloudPlaybackCursor(1, 2), merged.cursorAt(6))
    }

    @Test
    fun `drops isolated silent cue with gaps on both sides`() {
        val merged = ReadAloudPlaybackQueue.from(
            listOf(
                item("你好", 0),
                item("……", 5),
                item("再见", 10),
            )
        )

        assertEquals(2, merged.cues.size)
        // 缝隙由 cursorAt 对齐到下一 cue，丢弃碎段不会产生落点空洞
        assertEquals(ReadAloudPlaybackCursor(1, 0), merged.cursorAt(5))
    }

    @Test
    fun `all-silent plan collapses to empty queue`() {
        val merged = ReadAloudPlaybackQueue.from(
            listOf(item("。", 0), item("……", 1))
        )

        assertEquals(true, merged.isEmpty)
    }

    private fun item(
        text: String,
        start: Int,
        paragraph: Int,
        role: SpeechRoleType = SpeechRoleType.Narrator,
    ): SpeechPlanItem = SpeechPlanItem(
        segment = ChapterSpeechSegment(
            id = "$start",
            analysisId = "analysis",
            bookUrl = "book",
            chapterIndex = 1,
            paragraphIndex = paragraph,
            start = 0,
            end = text.length,
            chapterPosition = start,
            text = text,
            roleType = role,
            source = SpeechResolutionSource.Rule,
        ),
        voice = null,
        fallbackVoices = emptyList(),
    )
}
