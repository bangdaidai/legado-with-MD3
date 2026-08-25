package io.legado.app.data.repository.ai

import androidx.annotation.Keep
import io.legado.app.domain.gateway.AiWebSearchGateway
import io.legado.app.domain.gateway.WebSearchSettingsGateway
import io.legado.app.domain.model.AiHttpException
import io.legado.app.domain.model.AiWebSearchHit
import io.legado.app.domain.model.AiWebSearchQuery
import io.legado.app.domain.model.AiWebSearchResult
import io.legado.app.domain.model.settings.WebSearchSettings
import io.legado.app.data.repository.ai.AiLogEntry
import io.legado.app.data.repository.ai.AiLogRepository
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tavily Search API 实现。
 *
 * 用共享的 [okHttpClient] 而不是 [aiOkHttpClient]：后者把 read/call timeout 设成 0 是为了
 * 长时间的 LLM 流式响应，而检索是一次短请求，需要正常超时兜底。
 */
class TavilySearchRepository(
    private val settingsGateway: WebSearchSettingsGateway,
    private val aiLogRepository: AiLogRepository,
) : AiWebSearchGateway {

    override val isConfigured: Boolean
        get() = settingsGateway.currentSettings.isConfigured

    override suspend fun search(query: AiWebSearchQuery): Result<AiWebSearchResult> {
        val start = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) {
            runCatching { searchInternal(query) }
                .onFailure { if (it is CancellationException) throw it }
        }
        aiLogRepository.record(
            AiLogEntry(
                timeMillis = start,
                kind = "webSearch",
                summary = query.query,
                success = result.isSuccess,
                durationMillis = System.currentTimeMillis() - start,
                error = result.exceptionOrNull()?.message,
            )
        )
        return result
    }

    private suspend fun searchInternal(query: AiWebSearchQuery): AiWebSearchResult {
        val settings = settingsGateway.currentSettings
        require(settings.isConfigured) { "Web search is not configured" }
        val keyword = query.query.trim()
        require(keyword.isNotBlank()) { "Search query is empty" }

        val body = mapOf(
            "query" to keyword,
            "topic" to (query.topic?.takeIf { it in WebSearchSettings.topics } ?: settings.topic),
            "search_depth" to (
                query.searchDepth?.takeIf { it in WebSearchSettings.depths } ?: settings.searchDepth
                ),
            "max_results" to (query.maxResults ?: settings.maxResults)
                .coerceIn(WebSearchSettings.MIN_RESULTS, WebSearchSettings.MAX_RESULTS),
            "include_answer" to true,
            "include_raw_content" to false,
        )

        return retryWithBackoff(maxAttempts = 2) {
            val response = okHttpClient.newCallStrResponse {
                url(settings.baseUrl.toTavilySearchUrl())
                postJson(GSON.toJson(body))
                addHeaders(
                    mapOf(
                        "Authorization" to "Bearer ${settings.apiKey.trim()}",
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                    )
                )
            }
            if (!response.isSuccessful()) {
                throw AiHttpException(response.code(), response.message(), response.body)
            }
            val parsed = GSON.fromJson(response.body, TavilySearchResponse::class.java)
                ?: throw IllegalStateException("Tavily returned an unreadable response")
            parsed.toDomain(keyword)
        }
    }
}

/** Tavily 的检索端点固定是 `/search`；允许用户填带或不带该后缀的 baseUrl。 */
internal fun String.toTavilySearchUrl(): String {
    val normalized = trim().trimEnd('/')
    return if (normalized.endsWith("/search")) normalized else "$normalized/search"
}

@Keep
internal data class TavilySearchResponse(
    val query: String?,
    val answer: String?,
    val results: List<TavilySearchItem>?,
) {
    fun toDomain(fallbackQuery: String): AiWebSearchResult = AiWebSearchResult(
        query = query?.takeIf { it.isNotBlank() } ?: fallbackQuery,
        answer = answer?.trim()?.takeIf { it.isNotEmpty() },
        hits = results.orEmpty().mapNotNull { it.toDomain() },
    )
}

@Keep
internal data class TavilySearchItem(
    val title: String?,
    val url: String?,
    val content: String?,
    val score: Double?,
) {
    fun toDomain(): AiWebSearchHit? {
        val link = url?.trim().orEmpty()
        if (link.isEmpty()) return null
        return AiWebSearchHit(
            title = title?.trim().orEmpty().ifEmpty { link },
            url = link,
            content = content?.trim().orEmpty(),
            score = score,
        )
    }
}
