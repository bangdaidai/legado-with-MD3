package io.legado.app.model.jsSource

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSourceEngineTest {

    private fun newSource(mainJs: String?): BookSource {
        return BookSource(
            bookSourceUrl = "https://example.com",
            bookSourceName = "示例JS书源",
            mainJs = mainJs,
        )
    }

    @Test
    fun `callFunction returns string result directly`() {
        val engine = JsSourceEngine(
            newSource(
                """
                function greet(name) { return "hello " + name; }
                """.trimIndent()
            )
        )
        assertEquals("hello world", engine.callFunction("greet", listOf("name" to "world")))
    }

    @Test
    fun `callFunction stringifies object and array results`() {
        val engine = JsSourceEngine(
            newSource(
                """
                function makeBook(key) {
                    return { name: "书名", bookUrl: "https://example.com/" + key };
                }
                function makeList() {
                    return [
                        { title: "第一章", url: "/1.html" },
                        { title: "第二章", url: "/2.html" }
                    ];
                }
                """.trimIndent()
            )
        )
        val book = engine.callFunction("makeBook", listOf("key" to "42"))
        assertEquals("""{"name":"书名","bookUrl":"https://example.com/42"}""", book)
        val list = engine.callFunction("makeList", emptyList())
        assertEquals(
            """[{"title":"第一章","url":"/1.html"},{"title":"第二章","url":"/2.html"}]""",
            list,
        )
    }

    @Test
    fun `runtime bindings are visible to script`() {
        val engine = JsSourceEngine(
            newSource(
                """
                function getBaseUrl() { return baseUrl; }
                function getSourceUrl() { return source.bookSourceUrl; }
                function getSourceApiUrl() { return sourceApi.bookSourceName; }
                function hasJava() { return typeof java === "object"; }
                function hasCookie() { return typeof cookie === "object"; }
                function hasCache() { return typeof cache === "object"; }
                """.trimIndent()
            )
        )
        assertEquals("https://example.com", engine.callFunction("getBaseUrl", emptyList()))
        assertEquals("https://example.com", engine.callFunction("getSourceUrl", emptyList()))
        assertEquals("示例JS书源", engine.callFunction("getSourceApiUrl", emptyList()))
        assertEquals("object", engine.callFunction("hasJava", emptyList()))
        assertEquals("object", engine.callFunction("hasCookie", emptyList()))
        assertEquals("object", engine.callFunction("hasCache", emptyList()))
    }

    @Test
    fun `entity args are readable from script`() {
        val engine = JsSourceEngine(
            newSource(
                """
                function chapterTitle(chapter) { return chapter.title; }
                function chapterUrl(chapter, book, nextChapterUrl) { return nextChapterUrl; }
                """.trimIndent()
            )
        )
        val chapter = BookChapter(title = "第一章", url = "/1.html")
        assertEquals(
            "第一章",
            engine.callFunction("chapterTitle", listOf("chapter" to chapter)),
        )
        assertEquals(
            "next",
            engine.callFunction(
                "chapterUrl",
                listOf(
                    "chapter" to chapter,
                    "book" to io.legado.app.data.entities.Book(),
                    "nextChapterUrl" to "next",
                ),
            ),
        )
    }

    @Test
    fun `missing function throws with clear message`() {
        val engine = JsSourceEngine(newSource("function exist() { return 1; }"))
        val error = runCatching { engine.callFunction("missing", emptyList()) }.exceptionOrNull()
        assertTrue(error is NoStackTraceException)
        assertTrue(error!!.message!!.contains("missing"))
    }

    @Test
    fun `callFunctionIfExists returns null when function absent`() {
        val engine = JsSourceEngine(newSource("function exist() { return 1; }"))
        assertNull(engine.callFunctionIfExists("missing", emptyList()))
        assertNotNull(engine.callFunctionIfExists("exist", emptyList()))
    }

    @Test
    fun `blank mainJs throws`() {
        val engine = JsSourceEngine(newSource("  "))
        val error = runCatching { engine.callFunction("search", emptyList()) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("mainJs"))
    }

    @Test
    fun `script scope is fresh between calls`() {
        val engine = JsSourceEngine(
            newSource(
                """
                var counter = 0;
                function bump() { counter = counter + 1; return String(counter); }
                """.trimIndent()
            )
        )
        assertEquals("1", engine.callFunction("bump", emptyList()))
        assertEquals("1", engine.callFunction("bump", emptyList()))
    }
}
