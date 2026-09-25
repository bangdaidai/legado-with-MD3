package io.legado.app.data.repository.ai

import io.legado.app.domain.model.AiCallTrace
import io.legado.app.domain.model.AiHttpException
import io.legado.app.domain.model.AiProviderConfig
import io.legado.app.domain.model.aiFailureKind
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

/**
 * Round-robin key rotator for providers with multiple API keys.
 * Keys are comma-separated in the provider's apiKey field.
 */
internal class KeyRotator(rawKey: String) {

    private val keys: List<String> = rawKey
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }

    private var index = 0

    val currentKey: String
        get() = keys[index % keys.size]

    val hasMultipleKeys: Boolean
        get() = keys.size > 1

    /** Advance to the next key. Returns the new current key. */
    fun rotate(): String {
        if (keys.size > 1) {
            index = (index + 1) % keys.size
        }
        return currentKey
    }
}

/**
 * Retry a block with exponential backoff + jitter.
 * Retries only when [io.legado.app.domain.model.aiFailureKind] classifies the failure as
 * retryable: rate limit, provider 5xx, timeout and other network IO errors.
 * Coroutine cancellation is rethrown immediately and never retried.
 * If [keyRotator] is provided and has multiple keys, rotates key on each retry.
 *
 * @param maxAttempts Total attempts (1 = no retry, 2 = one retry, etc.)
 * @param baseDelayMs Base delay in milliseconds
 * @param maxDelayMs Maximum delay cap
 * @param onRetry Called before each retry with (attempt, delayMs, exception)
 */
internal suspend fun <T> retryWithBackoff(
    maxAttempts: Int = 3,
    baseDelayMs: Long = 1_000,
    maxDelayMs: Long = 30_000,
    keyRotator: KeyRotator? = null,
    onRetry: (suspend (attempt: Int, delayMs: Long, error: Exception) -> Unit)? = null,
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    for (attempt in 1..maxAttempts) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastException = e
            if (attempt >= maxAttempts) break
            if (!e.aiFailureKind().isRetryable) break

            // Rotate key if available
            if (keyRotator != null && keyRotator.hasMultipleKeys) {
                keyRotator.rotate()
            }

            // Exponential backoff with jitter
            val exponentialDelay = baseDelayMs * (1L shl (attempt - 1))
            val cappedDelay = min(exponentialDelay, maxDelayMs)
            val jitter = Random.nextLong(0, cappedDelay / 4 + 1)
            val totalDelay = cappedDelay + jitter

            onRetry?.invoke(attempt, totalDelay, e)
            delay(totalDelay)
        }
    }
    throw lastException ?: Exception("Retry failed after $maxAttempts attempts")
}

/**
 * 拉取模型列表的候选端点，按序尝试（参照 legado_NG 的 AiModelEndpointResolver）。
 * 用户显式配置了 modelsPath / modelsUrl 时只认用户配置；否则按 baseUrl 形态生成候选：
 * 版本段结尾（/v1 等）直接拼 /models；无版本段先试 /models（存量行为）再试 /v1/models
 * （自动补救漏写版本段的配置）；DeepSeek 官方域名用其固定地址。
 */
internal fun aiModelsEndpointCandidates(provider: AiProviderConfig): List<String> {
    provider.modelsPath?.let { return listOf(provider.baseUrl + it) }
    provider.modelsUrl?.let { return listOf(it) }
    val base = provider.baseUrl.trim().trimEnd('/')
    if (base.isBlank()) return emptyList()
    val candidates = linkedSetOf<String>()
    when {
        base.contains("api.deepseek.com", ignoreCase = true) ->
            candidates += "https://api.deepseek.com/models"

        Regex(""".*/v\d+$""", RegexOption.IGNORE_CASE).matches(base) -> {
            candidates += "$base/models"
            if (!base.endsWith("/v1", ignoreCase = true)) {
                candidates += "$base/v1/models"
            }
        }

        else -> {
            candidates += "$base/models"
            candidates += "$base/v1/models"
        }
    }
    return candidates.toList()
}

/**
 * 按候选端点依次拉取模型列表：404/405 表示该候选不存在，换下一个；401/403 鉴权失败
 * 直接抛出（换端点结果相同）；其余错误记录后换下一个，全部失败抛最后一个。
 * 每个候选只请求一次、不做退避重试——换候选本身就是重试维度，避免对假端点反复请求。
 */
internal suspend fun <T> tryModelsEndpoints(
    provider: AiProviderConfig,
    trace: AiCallTrace,
    authHeaders: Map<String, String>,
    parse: (String) -> T
): T {
    val candidates = aiModelsEndpointCandidates(provider)
    require(candidates.isNotEmpty()) { "AI provider configuration incomplete: no models endpoint candidate" }
    var lastError: Throwable? = null
    for (url in candidates) {
        try {
            trace.mark("已发送请求")
            val response = okHttpClient.newCallStrResponse {
                url(url)
                addHeaders(provider.headers + provider.customHeaders + authHeaders)
            }
            trace.mark("已收到响应")
            if (!response.isSuccessful()) {
                throw AiHttpException(response.code(), response.message(), response.body)
            }
            return parse(response.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiHttpException) {
            if (e.statusCode == 401 || e.statusCode == 403) throw e
            lastError = e
        } catch (e: Throwable) {
            lastError = e
        }
    }
    throw lastError ?: IllegalStateException("No models endpoint candidates")
}
