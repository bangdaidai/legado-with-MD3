package io.legado.app.model.jsSource

import io.legado.app.constant.BookSourceType
import io.legado.app.exception.NoStackTraceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsSourceConfigTest {

    @Test
    fun `extracts config and keeps full script as mainJs`() {
        val script = """
            var config = {
                bookSourceUrl: "https://example.com",
                bookSourceName: "示例JS书源",
                bookSourceType: 0,
                ruleSearch: { bookList: "should be stripped" },
                mainJs: "should be stripped"
            };
            function search(key, page) { return []; }
            function getChapters(book) { return []; }
            function getContent(chapter, book, nextChapterUrl) { return "ok"; }
        """.trimIndent()
        val source = JsSourceConfig.extract(script)
        assertEquals("https://example.com", source.bookSourceUrl)
        assertEquals("示例JS书源", source.bookSourceName)
        assertEquals(BookSourceType.default, source.bookSourceType)
        assertEquals(script, source.mainJs)
        assertNull(source.ruleSearch)
    }

    @Test
    fun `supports legacy source property`() {
        val script = """
            var source = {
                bookSourceUrl: "https://legacy.com",
                bookSourceName: "旧版JS书源"
            };
            function search(key, page) { return []; }
            function getChapters(book) { return []; }
            function getContent(chapter, book, nextChapterUrl) { return "ok"; }
        """.trimIndent()
        val source = JsSourceConfig.extract(script)
        assertEquals("https://legacy.com", source.bookSourceUrl)
        assertEquals("旧版JS书源", source.bookSourceName)
    }

    @Test
    fun `missing config throws`() {
        val script = """
            function search(key, page) { return []; }
            function getChapters(book) { return []; }
            function getContent(chapter, book, nextChapterUrl) { return ""; }
        """.trimIndent()
        val error = runCatching { JsSourceConfig.extract(script) }.exceptionOrNull()
        assertTrue(error is NoStackTraceException)
        assertTrue(error!!.message!!.contains("config"))
    }

    @Test
    fun `missing required function throws`() {
        val script = """
            var config = {
                bookSourceUrl: "https://example.com",
                bookSourceName: "示例JS书源"
            };
            function search(key, page) { return []; }
        """.trimIndent()
        val error = runCatching { JsSourceConfig.extract(script) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("getChapters"))
    }

    @Test
    fun `file source requires getBookInfo instead of chapters`() {
        val script = """
            var config = {
                bookSourceUrl: "https://file.com",
                bookSourceName: "文件源",
                bookSourceType: 3
            };
            function search(key, page) { return []; }
            function getBookInfo(book) { return {}; }
        """.trimIndent()
        val source = JsSourceConfig.extract(script)
        assertEquals(BookSourceType.file, source.bookSourceType)
    }

    @Test
    fun `exploreUrl without explore function throws`() {
        val script = """
            var config = {
                bookSourceUrl: "https://example.com",
                bookSourceName: "示例JS书源",
                exploreUrl: [{ title: "分类", url: "https://example.com/cat" }]
            };
            function search(key, page) { return []; }
            function getChapters(book) { return []; }
            function getContent(chapter, book, nextChapterUrl) { return "ok"; }
        """.trimIndent()
        val error = runCatching { JsSourceConfig.extract(script) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("explore"))
    }

    @Test
    fun `exploreUrl array normalized to json string`() {
        val script = """
            var config = {
                bookSourceUrl: "https://example.com",
                bookSourceName: "示例JS书源",
                exploreUrl: [{ title: "分类", url: "https://example.com/cat" }]
            };
            function search(key, page) { return []; }
            function explore(url, page) { return []; }
            function getChapters(book) { return []; }
            function getContent(chapter, book, nextChapterUrl) { return "ok"; }
        """.trimIndent()
        val source = JsSourceConfig.extract(script)
        assertNotNull(source.exploreUrl)
        assertTrue(source.exploreUrl!!.contains("分类"))
    }

    @Test
    fun `static loginUi without login function throws`() {
        val script = """
            var config = {
                bookSourceUrl: "https://example.com",
                bookSourceName: "示例JS书源",
                loginUi: [{ name: "用户名", type: "text" }]
            };
            function search(key, page) { return []; }
            function getChapters(book) { return []; }
            function getContent(chapter, book, nextChapterUrl) { return "ok"; }
        """.trimIndent()
        val error = runCatching { JsSourceConfig.extract(script) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("login"))
    }

    @Test
    fun `dynamic loginUi function is rejected explicitly`() {
        val script = """
            var config = {
                bookSourceUrl: "https://example.com",
                bookSourceName: "示例JS书源"
            };
            function search(key, page) { return []; }
            function getChapters(book) { return []; }
            function getContent(chapter, book, nextChapterUrl) { return "ok"; }
            function loginUi(state) { return { rows: [] }; }
            function loginAction(action, state, form) { return {}; }
        """.trimIndent()
        val error = runCatching { JsSourceConfig.extract(script) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("动态登录"))
    }

    @Test
    fun `top level script error is wrapped with message`() {
        val script = """
            var config = {
                bookSourceUrl: "https://example.com",
                bookSourceName: "示例JS书源"
            };
            throw "导入期异常";
        """.trimIndent()
        val error = runCatching { JsSourceConfig.extract(script) }.exceptionOrNull()
        assertTrue(error is NoStackTraceException)
        assertTrue(error!!.message!!.contains("导入期异常"))
    }
}
