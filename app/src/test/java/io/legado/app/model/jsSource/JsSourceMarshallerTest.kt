package io.legado.app.model.jsSource

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSourceMarshallerTest {

    private val source = BookSource(
        bookSourceUrl = "https://example.com",
        bookSourceName = "示例JS书源",
        customOrder = 7,
    )

    @Test
    fun `parseSearchBooks fills origin fields and drops invalid entries`() {
        val json = """
            [
                {"name": "书名", "bookUrl": "https://example.com/book/1", "author": "作者", "kind": "玄幻"},
                {"name": "", "bookUrl": "https://example.com/book/2"},
                {"bookUrl": "https://example.com/book/3"},
                "not-an-object"
            ]
        """.trimIndent()
        val books = JsSourceMarshaller.parseSearchBooks(json, source)
        assertEquals(1, books.size)
        val book = books[0]
        assertEquals("书名", book.name)
        assertEquals("https://example.com/book/1", book.bookUrl)
        assertEquals("https://example.com", book.origin)
        assertEquals("示例JS书源", book.originName)
        assertEquals(7, book.originOrder)
        assertEquals(BookType.text, book.type)
    }

    @Test
    fun `parseSearchBooks rejects non array result`() {
        val error = runCatching {
            JsSourceMarshaller.parseSearchBooks("""{"name": "x"}""", source)
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("不是数组"))
    }

    @Test
    fun `parseSearchBooks type field overrides source type when valid`() {
        val json = """[{"name": "书名", "bookUrl": "u", "type": ${BookType.audio}}]"""
        val books = JsSourceMarshaller.parseSearchBooks(json, source)
        assertEquals(BookType.audio, books[0].type)
    }

    @Test
    fun `parseChapters absolutizes urls and assigns index`() {
        val book = Book(bookUrl = "https://example.com/book/1", tocUrl = "https://example.com/book/1/toc")
        val json = """
            [
                {"title": "第一章", "url": "/book/1/1.html"},
                {"title": "卷一", "url": "卷一", "isVolume": true},
                {"title": "", "url": "invalid"},
                {"title": "第二章", "url": "https://other.com/2.html"}
            ]
        """.trimIndent()
        val chapters = JsSourceMarshaller.parseChapters(json, book, source)
        assertEquals(3, chapters.size)
        assertEquals("https://example.com/book/1/1.html", chapters[0].url)
        assertEquals(0, chapters[0].index)
        assertEquals("卷一", chapters[1].url)
        assertEquals("https://other.com/2.html", chapters[2].url)
        assertEquals(book.bookUrl, chapters[0].bookUrl)
        assertEquals(book.tocUrl, chapters[0].baseUrl)
    }

    @Test
    fun `mergeBookInfo merges fields and absolutizes downloadUrls`() {
        val book = Book(bookUrl = "https://example.com/book/1", author = "")
        val json = """
            {
                "author": "作者",
                "intro": "简介",
                "tocUrl": "https://example.com/book/1/toc",
                "downloadUrls": ["/files/1.epub", "javascript:void(0)"],
                "variable": {"token": "abc"}
            }
        """.trimIndent()
        JsSourceMarshaller.mergeBookInfo(book, json, source, canReName = false)
        assertEquals("作者", book.author)
        assertEquals("简介", book.intro)
        assertEquals("https://example.com/book/1/toc", book.tocUrl)
        assertEquals(listOf("https://example.com/files/1.epub"), book.downloadUrls)
        assertEquals("abc", book.variableMap["token"])
        assertEquals("""{"token":"abc"}""", book.variable)
    }

    @Test
    fun `mergeBookInfo keeps author when not renaming and author exists`() {
        val book = Book(bookUrl = "https://example.com/book/1", author = "原作者")
        val json = """{"author": "新作者", "name": "新书名"}"""
        JsSourceMarshaller.mergeBookInfo(book, json, source, canReName = false)
        assertEquals("原作者", book.author)
        assertEquals("", book.name)
    }

    @Test
    fun `mergeBookInfo rejects non object result`() {
        val error = runCatching {
            JsSourceMarshaller.mergeBookInfo(Book(), "[1,2]", source, canReName = true)
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("不是对象"))
    }

    @Test
    fun `validateBookType rejects zero and unknown bits`() {
        assertEquals(null, JsSourceMarshaller.validateBookType(0))
        assertEquals(null, JsSourceMarshaller.validateBookType(0x10000))
        assertEquals(BookType.text, JsSourceMarshaller.validateBookType(BookType.text))
    }
}
