package io.legado.app.model

import io.legado.app.data.entities.BookMarking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备注快照的两条语义：只留有备注的划线，以及**书键必须匹配**。
 * 后者是这份快照唯一的正确性风险——换书后新书的备注流第一条到达之前，
 * 上一本的快照还在，若不校键就会给新书凭空画出角标。
 */
class ReaderMarkingNoteStateTest {

    @After
    fun tearDown() {
        ReaderMarkingNoteState.clear()
    }

    private fun marking(
        id: String,
        note: String,
        enabled: Boolean = true,
    ) = BookMarking(
        id = id,
        bookUrl = "url",
        bookName = "book",
        bookAuthor = "author",
        chapterIndex = 0,
        anchorJson = "{}",
        note = note,
        enabled = enabled,
    )

    @Test
    fun `only enabled markings with a non blank note are exposed`() {
        ReaderMarkingNoteState.update(
            "book",
            "author",
            listOf(
                marking("a", "有备注"),
                marking("b", "   "),
                marking("c", "停用", enabled = false),
            ),
        )

        assertEquals(mapOf("a" to "有备注"), ReaderMarkingNoteState.notesFor("book", "author"))
        assertEquals("有备注", ReaderMarkingNoteState.noteFor("a"))
        assertNull(ReaderMarkingNoteState.noteFor("b"))
        assertNull(ReaderMarkingNoteState.noteFor("c"))
    }

    @Test
    fun `notes of another book are never served`() {
        ReaderMarkingNoteState.update("book", "author", listOf(marking("a", "旧书的备注")))

        assertTrue(ReaderMarkingNoteState.notesFor("other", "author").isEmpty())
        assertTrue(ReaderMarkingNoteState.notesFor("book", "otherAuthor").isEmpty())
    }

    @Test
    fun `cleared snapshot serves nothing`() {
        ReaderMarkingNoteState.update("book", "author", listOf(marking("a", "备注")))
        ReaderMarkingNoteState.clear()

        assertTrue(ReaderMarkingNoteState.notesFor("book", "author").isEmpty())
        assertNull(ReaderMarkingNoteState.noteFor("a"))
    }
}