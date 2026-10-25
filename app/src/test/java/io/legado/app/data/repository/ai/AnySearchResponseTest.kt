package io.legado.app.data.repository.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnySearchResponseTest {

    @Test
    fun searchPathAcceptsDomainVersionAndFullEndpoint() {
        assertEquals(
            "https://api.anysearch.com/v1/search",
            "https://api.anysearch.com".toAnySearchSearchUrl()
        )
        assertEquals(
            "https://api.anysearch.com/v1/search",
            "https://api.anysearch.com/".toAnySearchSearchUrl()
        )
        assertEquals(
            "https://api.anysearch.com/v1/search",
            "https://api.anysearch.com/v1".toAnySearchSearchUrl()
        )
        assertEquals(
            "https://api.anysearch.com/v1/search",
            "  https://api.anysearch.com/v1/search/  ".toAnySearchSearchUrl()
        )
    }

    @Test
    fun hitsAreMappedWithContentAndSnippetFallback() {
        val parsed = AnySearchResponse(
            code = 0,
            message = "success",
            data = AnySearchData(
                results = listOf(
                    AnySearchItem(
                        title = " Title ",
                        url = " https://a.example ",
                        snippet = "snippet",
                        content = " full body "
                    ),
                    AnySearchItem(
                        title = null,
                        url = "https://b.example",
                        snippet = " only snippet ",
                        content = "  "
                    ),
                    AnySearchItem(title = "no url", url = " ", snippet = null, content = null)
                )
            )
        ).toDomain(fallbackQuery = "fallback")

        assertEquals("fallback", parsed.query)
        assertNull(parsed.answer)
        assertEquals(2, parsed.hits.size)
        assertEquals("https://a.example", parsed.hits[0].url)
        assertEquals("full body", parsed.hits[0].content)
        assertEquals("https://b.example", parsed.hits[1].title)
        assertEquals("only snippet", parsed.hits[1].content)
        assertNull(parsed.hits[0].score)
    }

    @Test
    fun missingDataIsTreatedAsEmpty() {
        val parsed = AnySearchResponse(code = 0, message = "success", data = null)
            .toDomain(fallbackQuery = "fallback")

        assertTrue(parsed.isEmpty)
        assertEquals("fallback", parsed.query)
    }
}
