package io.legado.app.data.repository.ai

import io.legado.app.domain.model.aiFailureKind
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
