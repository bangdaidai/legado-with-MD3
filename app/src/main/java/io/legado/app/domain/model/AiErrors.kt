package io.legado.app.domain.model

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/**
 * AI 供应商返回的非 2xx 响应。
 *
 * 之前各 handler 统一抛 `Exception("HTTP 429: ...")`，下游只能靠 message 子串判断错误
 * 类型，后果是 [IOException] 一律不重试、500/504 被漏掉，且重试工具与章节翻译各有一套
 * 互相冲突的分类。这里把状态码与供应商原始错误体作为字段带出来，分类统一走 [aiFailureKind]。
 *
 * [providerMessage] 是响应体原文，供应商的具体错误码（例如 `context_length_exceeded`）
 * 只出现在这里，不在 HTTP status message 里。
 */
class AiHttpException(
    val statusCode: Int,
    val statusMessage: String? = null,
    val providerMessage: String? = null,
) : Exception(formatAiHttpMessage(statusCode, statusMessage, providerMessage))

private const val MAX_PROVIDER_MESSAGE_CHARS = 500

private fun formatAiHttpMessage(
    statusCode: Int,
    statusMessage: String?,
    providerMessage: String?,
): String = buildString {
    append("HTTP ").append(statusCode)
    statusMessage?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    providerMessage?.takeIf { it.isNotBlank() }?.let {
        append(" - ").append(it.trim().take(MAX_PROVIDER_MESSAGE_CHARS))
    }
}

/** AI 请求失败的归一化原因，决定是否值得重试。 */
enum class AiFailureKind {
    /** 限流，退避后重试通常有效。 */
    RATE_LIMIT,

    /** 供应商 5xx，含 Anthropic 的 529 overloaded。 */
    SERVER_ERROR,

    /** 读写超时。移动网络下最常见，必须重试。 */
    TIMEOUT,

    /** 连接失败、DNS 失败、连接被重置等其它 IO 异常。 */
    NETWORK,

    /** 401/403，密钥或权限问题，重试只是浪费配额。 */
    AUTH,

    /** 输入超出模型上下文窗口，必须缩短输入而不是重试。 */
    CONTEXT_OVERFLOW,

    /** 其它 4xx，请求本身有问题。 */
    CLIENT_ERROR,

    /** 无法归类。保守起见不重试。 */
    UNKNOWN;

    val isRetryable: Boolean
        get() = when (this) {
            RATE_LIMIT, SERVER_ERROR, TIMEOUT, NETWORK -> true
            AUTH, CONTEXT_OVERFLOW, CLIENT_ERROR, UNKNOWN -> false
        }
}

/**
 * 各家供应商报上下文超限时的措辞。没有统一错误码，只能匹配子串，
 * 因此这里只收录能确认的表述，匹配不到就退回状态码分类。
 */
private val CONTEXT_OVERFLOW_MARKERS = listOf(
    "context_length_exceeded",
    "maximum context length",
    "context length",
    "context window",
    "prompt is too long",
    "input is too long",
    "exceed context limit",
    "too many tokens",
    "reduce the length of the messages",
)

/**
 * 把任意异常归一化成 [AiFailureKind]。
 *
 * 注意：[kotlinx.coroutines.CancellationException] 不在这里处理 —— 协程取消必须由调用方
 * 直接重抛，不能当作可重试的失败。
 */
fun Throwable.aiFailureKind(): AiFailureKind {
    if (this is AiHttpException) {
        detectContextOverflow(providerMessage ?: message)?.let { return it }
        return when (statusCode) {
            401, 403 -> AiFailureKind.AUTH
            408, 425 -> AiFailureKind.TIMEOUT
            429 -> AiFailureKind.RATE_LIMIT
            in 500..599 -> AiFailureKind.SERVER_ERROR
            in 400..499 -> AiFailureKind.CLIENT_ERROR
            else -> AiFailureKind.UNKNOWN
        }
    }
    // SocketTimeoutException 继承 InterruptedIOException 继承 IOException，顺序不能反。
    if (this is SocketTimeoutException || this is InterruptedIOException) {
        return AiFailureKind.TIMEOUT
    }
    if (this is IOException) return AiFailureKind.NETWORK
    return detectContextOverflow(message) ?: AiFailureKind.UNKNOWN
}

private fun detectContextOverflow(text: String?): AiFailureKind? {
    val lowered = text?.lowercase() ?: return null
    return if (CONTEXT_OVERFLOW_MARKERS.any { it in lowered }) {
        AiFailureKind.CONTEXT_OVERFLOW
    } else {
        null
    }
}
