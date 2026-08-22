package io.legado.app.data.repository.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TavilySearchResponseTest {

    @Test
    fun searchPathIsAppendedOnlyWhenMissing() {
        assertEquals("https://api.tavily.com/search", "https://api.tavily.com".toTavilySearchUrl())
        assertEquals("https://api.tavily.com/search", "https://api.tavily.com/".toTavilySearchUrl())
        assertEquals(
            "https://api.tavily.com/search",
            "  https://api.tavily.com/search/  ".toTavilySearchUrl()
        )
    }

    @Test
    fun hitsWithoutUrlAreDropped() {
        val parsed = TavilySearchResponse(
            query = "legado",
            answer = "  a summary  ",
            results = listOf(
                TavilySearchItem(title = "no url", url = "  ", content = "x", score = 0.9),
                TavilySearchItem(title = " Title ", url = " https://a.example ", content = " body ", score = 0.5)
            )
        ).toDomain(fallbackQuery = "fallback")

        assertEquals(1, parsed.hits.size)
        assertEquals("Title", parsed.hits[0].title)
        assertEquals("https://a.example", parsed.hits[0].url)
        assertEquals("body", parsed.hits[0].content)
        assertEquals("a summary", parsed.answer)
    }

    @Test
    fun missingTitleFallsBackToTheUrl() {
        val parsed = TavilySearchResponse(
            query = null,
            answer = null,
            results = listOf(TavilySearchItem(title = null, url = "https://b.example", content = null, score = null))
        ).toDomain(fallbackQuery = "fallback")

        assertEquals("fallback", parsed.query)
        assertEquals("https://b.example", parsed.hits[0].title)
        assertEquals("", parsed.hits[0].content)
        assertNull(parsed.answer)
    }

    @Test
    fun blankAnswerAndNoResultsIsTreatedAsEmpty() {
        val parsed = TavilySearchResponse(query = "q", answer = "   ", results = emptyList())
            .toDomain(fallbackQuery = "fallback")

        assertNull(parsed.answer)
        assertTrue(parsed.isEmpty)
    }
}
