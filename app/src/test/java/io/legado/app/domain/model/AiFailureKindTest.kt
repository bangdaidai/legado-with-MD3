package io.legado.app.domain.model

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 覆盖 P0 重试 bug：移动网络下的 IO 异常此前一律不重试，500/504 也被漏掉。
 */
class AiFailureKindTest {

    @Test
    fun socketTimeoutIsRetryableAsTimeout() {
        val kind = SocketTimeoutException("timeout").aiFailureKind()

        assertEquals(AiFailureKind.TIMEOUT, kind)
        assertTrue(kind.isRetryable)
    }

    @Test
    fun connectionFailuresAreRetryableAsNetwork() {
        assertEquals(AiFailureKind.NETWORK, UnknownHostException("no dns").aiFailureKind())
        assertEquals(AiFailureKind.NETWORK, IOException("connection reset").aiFailureKind())
        assertTrue(AiFailureKind.NETWORK.isRetryable)
    }

    @Test
    fun serverErrorsIncludeFiveHundredAndGatewayTimeout() {
        listOf(500, 502, 503, 504, 529).forEach { code ->
            val kind = AiHttpException(code).aiFailureKind()
            assertEquals("HTTP $code should be a server error", AiFailureKind.SERVER_ERROR, kind)
            assertTrue("HTTP $code should be retryable", kind.isRetryable)
        }
    }

    @Test
    fun rateLimitIsRetryableButAuthIsNot() {
        assertEquals(AiFailureKind.RATE_LIMIT, AiHttpException(429).aiFailureKind())
        assertTrue(AiFailureKind.RATE_LIMIT.isRetryable)

        listOf(401, 403).forEach { code ->
            assertEquals(AiFailureKind.AUTH, AiHttpException(code).aiFailureKind())
        }
        assertFalse(AiFailureKind.AUTH.isRetryable)
    }

    @Test
    fun otherClientErrorsAreNotRetried() {
        val kind = AiHttpException(404, "Not Found").aiFailureKind()

        assertEquals(AiFailureKind.CLIENT_ERROR, kind)
        assertFalse(kind.isRetryable)
    }

    @Test
    fun contextOverflowIsDetectedFromProviderBodyAndNotRetried() {
        val openAi = AiHttpException(
            statusCode = 400,
            statusMessage = "Bad Request",
            providerMessage = """{"error":{"code":"context_length_exceeded"}}""",
        )
        val anthropic = AiHttpException(
            statusCode = 400,
            providerMessage = "prompt is too long: 210000 tokens > 200000 maximum",
        )

        assertEquals(AiFailureKind.CONTEXT_OVERFLOW, openAi.aiFailureKind())
        assertEquals(AiFailureKind.CONTEXT_OVERFLOW, anthropic.aiFailureKind())
        assertFalse(AiFailureKind.CONTEXT_OVERFLOW.isRetryable)
    }

    @Test
    fun providerBodyIsKeptInTheMessageForDiagnosis() {
        val error = AiHttpException(429, "Too Many Requests", "rate limit reached for gpt-4o")

        assertEquals(
            "HTTP 429: Too Many Requests - rate limit reached for gpt-4o",
            error.message,
        )
    }

    @Test
    fun unclassifiedFailuresAreNotRetried() {
        val kind = IllegalStateException("Empty AI response").aiFailureKind()

        assertEquals(AiFailureKind.UNKNOWN, kind)
        assertFalse(kind.isRetryable)
    }

    /**
     * 仓库层 withTimeout 造的超时不是 IOException，AI 调用又是唯一一处整体超时
     * （okhttp 的 readTimeout / callTimeout 都是 0），漏掉它等于超时永远不重试。
     */
    @Test
    fun coroutineTimeoutIsRetryableAsTimeout() = runBlocking {
        val error = try {
            withTimeout(1) { delay(1_000) }
            throw IllegalStateException("expected withTimeout to fire")
        } catch (e: TimeoutCancellationException) {
            e
        }

        val kind = error.aiFailureKind()

        assertEquals(AiFailureKind.TIMEOUT, kind)
        assertTrue(kind.isRetryable)
    }

    /** 真取消仍然不是超时：它必须留在不重试那一类，由调用方直接重抛。 */
    @Test
    fun plainCancellationIsStillNotATimeout() {
        val kind = CancellationException("Job was cancelled").aiFailureKind()

        assertEquals(AiFailureKind.UNKNOWN, kind)
        assertFalse(kind.isRetryable)
    }
}
