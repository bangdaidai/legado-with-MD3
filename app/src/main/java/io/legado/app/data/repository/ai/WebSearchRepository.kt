package io.legado.app.data.repository.ai

import androidx.annotation.Keep
import io.legado.app.domain.gateway.AiWebSearchGateway
import io.legado.app.domain.gateway.WebSearchSettingsGateway
import io.legado.app.domain.model.AiHttpException
import io.legado.app.domain.model.AiWebSearchHit
import io.legado.app.domain.model.AiWebSearchQuery
import io.legado.app.domain.model.AiWebSearchResult
import io.legado.app.domain.model.settings.WebSearchSettings
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 与 LLM 供应商无关的联网检索实现，按 [WebSearchSettings.provider] 分流。
 *
 * 用共享的 [okHttpClient] 而不是 [aiOkHttpClient]：后者把 read/call timeout 设成 0 是为了
 * 长时间的 LLM 流式响应，而检索是一次短请求，需要正常超时兜底。
 */
class WebSearchRepository(
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

        return when (settings.provider) {
            WebSearchSettings.PROVIDER_ANYSEARCH -> searchAnySearch(settings, keyword, query)
            else -> searchTavily(settings, keyword, query)
        }
    }

    private suspend fun searchTavily(
        settings: WebSearchSettings,
        keyword: String,
        query: AiWebSearchQuery,
    ): AiWebSearchResult {
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

    private suspend fun searchAnySearch(
        settings: WebSearchSettings,
        keyword: String,
        query: AiWebSearchQuery,
    ): AiWebSearchResult {
        val body = mapOf(
            "query" to keyword,
            // AnySearch 实际只认下划线参数；传驼峰会静默忽略并固定返回 10 条
            "max_results" to (query.maxResults ?: settings.maxResults)
                .coerceIn(WebSearchSettings.MIN_RESULTS, WebSearchSettings.MAX_RESULTS),
        )
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept", "application/json")
            // 匿名通道不传 Authorization；填了 key 才走认证额度
            settings.apiKey.trim().takeIf { it.isNotEmpty() }?.let {
                put("Authorization", "Bearer $it")
            }
        }

        return retryWithBackoff(maxAttempts = 2) {
            val response = okHttpClient.newCallStrResponse {
                url(settings.baseUrl.toAnySearchSearchUrl())
                postJson(GSON.toJson(body))
                addHeaders(headers)
            }
            if (!response.isSuccessful()) {
                throw AiHttpException(response.code(), response.message(), response.body)
            }
            val parsed = GSON.fromJson(response.body, AnySearchResponse::class.java)
                ?: throw IllegalStateException("AnySearch returned an unreadable response")
            val code = parsed.code
            if (code != null && code != 0) {
                throw IllegalStateException(
                    "AnySearch error $code: ${parsed.message.orEmpty()}"
                )
            }
            parsed.toDomain(keyword)
        }
    }
}

/** Tavily 的检索端点固定是 `/search`；允许用户填带或不带该后缀的 baseUrl。 */
internal fun String.toTavilySearchUrl(): String {
    val normalized = trim().trimEnd('/')
    return if (normalized.endsWith("/search")) normalized else "$normalized/search"
}

/** AnySearch 端点是 `/v1/search`；兼容用户填域名、`/v1` 或完整端点三种写法。 */
internal fun String.toAnySearchSearchUrl(): String {
    val normalized = trim().trimEnd('/')
    return when {
        normalized.isEmpty() -> "${WebSearchSettings.DEFAULT_ANYSEARCH_BASE_URL}/v1/search"
        normalized.endsWith("/v1/search", ignoreCase = true) -> normalized
        normalized.endsWith("/v1", ignoreCase = true) -> "$normalized/search"
        else -> "$normalized/v1/search"
    }
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

/**
 * AnySearch 的响应外壳：`{code, message, data:{results, metadata}}`。
 * 它不提供 answer，也不逐条打分，映射到领域模型时留空。
 */
@Keep
internal data class AnySearchResponse(
    val code: Int?,
    val message: String?,
    val data: AnySearchData?,
) {
    fun toDomain(fallbackQuery: String): AiWebSearchResult = AiWebSearchResult(
        query = fallbackQuery,
        hits = data?.results.orEmpty().mapNotNull { it.toDomain() },
    )
}

@Keep
internal data class AnySearchData(
    val results: List<AnySearchItem>?,
)

@Keep
internal data class AnySearchItem(
    val title: String?,
    val url: String?,
    val snippet: String?,
    val content: String?,
) {
    fun toDomain(): AiWebSearchHit? {
        val link = url?.trim().orEmpty()
        if (link.isEmpty()) return null
        return AiWebSearchHit(
            title = title?.trim().orEmpty().ifEmpty { link },
            url = link,
            content = content?.trim().orEmpty().ifEmpty { snippet?.trim().orEmpty() },
            score = null,
        )
    }
}
