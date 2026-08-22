package io.legado.app.data.repository.ai

import androidx.annotation.Keep
import io.legado.app.domain.gateway.AiStreamEvent
import io.legado.app.domain.model.AiAvailableModel
import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiGenerateResponse
import io.legado.app.domain.model.AiGenerationParams
import io.legado.app.domain.model.AiHttpException
import io.legado.app.domain.model.AiMessage
import io.legado.app.domain.model.AiMessageRole
import io.legado.app.domain.model.AiNativeWebSearchSupport
import io.legado.app.domain.model.AiProtocol
import io.legado.app.domain.model.AiProviderConfig
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.AiToolDefinition
import io.legado.app.domain.model.nativeWebSearchSupport
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.utils.GSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class OpenAiChatHandler : AiProtocolHandler {

    override val protocols = setOf(AiProtocol.OPENAI_CHAT_COMPLETIONS)

    override suspend fun generate(request: AiGenerateRequest): Result<AiGenerateResponse> =
        withContext(Dispatchers.IO) {
            runCatching { generateInternal(request) }
        }

    override suspend fun stream(
        request: AiGenerateRequest,
        emitEvent: suspend (AiStreamEvent) -> Unit
    ) {
        streamInternal(request, emitEvent)
    }

    override suspend fun fetchModels(provider: AiProviderConfig): Result<List<AiAvailableModel>> =
        withContext(Dispatchers.IO) {
            runCatching { fetchModelsInternal(provider) }
        }

    private suspend fun generateInternal(request: AiGenerateRequest): AiGenerateResponse {
        val provider = request.model.provider
        require(provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && request.model.modelId.isNotBlank()) {
            "OpenAI-compatible configuration incomplete: baseUrl, apiKey, and model are required"
        }
        val keyRotator = KeyRotator(provider.apiKey)
        val params = request.params
        val body = mutableMapOf<String, Any?>(
            "model" to request.model.modelId,
            "messages" to request.messages.toOpenAiChatMessages()
        )
        request.tools.takeIf { it.isNotEmpty() }?.let { body["tools"] = it.toOpenAiChatTools() }
        params.temperature?.let { body["temperature"] = it }
        params.maxOutputTokens?.let { body["max_tokens"] = it }
        params.topP?.let { body["top_p"] = it }
        if (hasReasoningCapability(request.model.capabilities)) {
            params.reasoningLevel.effortFor(provider)?.let {
                body["reasoning_effort"] = it
            }
        }
        body.applyThinkingSwitch(provider, params.reasoningLevel)
        body.applyZhipuThinking(provider, request.model.modelId, params.reasoningLevel)
        body.applyProviderWebSearch(provider, params)

        // 返回 null 代表「只有思考内容」，交给外层降级重试；其余失败照旧抛出
        suspend fun send(): AiGenerateResponse? =
            retryWithBackoff(maxAttempts = 3, keyRotator = keyRotator) {
                val response = aiOkHttpClient.newCallStrResponse {
                    url(provider.baseUrl + provider.chatPath)
                    postJson(GSON.toJson(body))
                    addHeaders(
                        provider.headers + provider.customHeaders + mapOf(
                            "Authorization" to "Bearer ${keyRotator.currentKey}",
                            "Content-Type" to "application/json"
                        )
                    )
                }
                if (!response.isSuccessful()) {
                    throw AiHttpException(response.code(), response.message(), response.body)
                }
                val json = GSON.fromJson(response.body, OpenAiChatResponse::class.java)
                val message = json?.choices?.firstOrNull()?.message
                val text = message?.content
                if (text.isNullOrBlank()) {
                    if (!message?.reasoningContent.isNullOrBlank()) {
                        null
                    } else {
                        throw Exception("Empty AI response")
                    }
                } else {
                    AiGenerateResponse(text = text, rawBody = response.body)
                }
            }

        send()?.let { return it }
        // 思考把 max_tokens 花光了：显式关掉思考、抬高输出上限再试一次，比直接失败划算
        body.applyThinkingSwitch(provider, AiReasoningLevel.OFF)
        body.applyZhipuThinking(provider, request.model.modelId, AiReasoningLevel.OFF)
        body.remove("reasoning_effort")
        body["max_tokens"] = maxOf(params.maxOutputTokens ?: 0, REASONING_FALLBACK_MAX_TOKENS)
        return send()
            ?: throw Exception("AI response contains only reasoning content; disable thinking for this model")
    }

    private suspend fun streamInternal(
        request: AiGenerateRequest,
        emitEvent: suspend (AiStreamEvent) -> Unit
    ) {
        val provider = request.model.provider
        require(provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && request.model.modelId.isNotBlank()) {
            "OpenAI-compatible configuration incomplete: baseUrl, apiKey, and model are required"
        }
        val keyRotator = KeyRotator(provider.apiKey)
        val params = request.params
        val body = mutableMapOf<String, Any?>(
            "model" to request.model.modelId,
            "messages" to request.messages.toOpenAiChatMessages(),
            "stream" to true
        )
        request.tools.takeIf { it.isNotEmpty() }?.let { body["tools"] = it.toOpenAiChatTools() }
        params.temperature?.let { body["temperature"] = it }
        params.maxOutputTokens?.let { body["max_tokens"] = it }
        params.topP?.let { body["top_p"] = it }
        if (hasReasoningCapability(request.model.capabilities)) {
            params.reasoningLevel.effortFor(provider)?.let {
                body["reasoning_effort"] = it
            }
        }
        body.applyThinkingSwitch(provider, params.reasoningLevel)
        body.applyZhipuThinking(provider, request.model.modelId, params.reasoningLevel)
        body.applyProviderWebSearch(provider, params)

        // For streaming, we retry before establishing the SSE connection.
        // Once streaming starts, errors are not retried (partial output would be confusing).
        val response = retryWithBackoff(maxAttempts = 3, keyRotator = keyRotator) {
            aiOkHttpClient.newCallResponse {
                url(provider.baseUrl + provider.chatPath)
                postJson(GSON.toJson(body))
                addHeaders(
                    provider.headers + provider.customHeaders + mapOf(
                        "Authorization" to "Bearer ${keyRotator.currentKey}",
                        "Content-Type" to "application/json"
                    )
                )
            }.also {
                if (!it.isSuccessful) {
                    val errorBody = runCatching { it.body.string() }.getOrNull()
                    it.close()
                    throw AiHttpException(it.code, it.message, errorBody)
                }
            }
        }
        try {
            response.readSseData { data ->
                val root = data.toJsonObject()
                root?.extractApiErrorMessage()?.let { throw Exception(it) }
                runCatching {
                    val chunk = GSON.fromJson(data, OpenAiChatStreamChunk::class.java)
                    val delta = chunk?.choices?.firstOrNull()?.delta
                    val reasoning = delta?.reasoning_content ?: delta?.reasoning
                    if (!reasoning.isNullOrEmpty()) {
                        emitEvent(AiStreamEvent.Reasoning(reasoning))
                    }
                    val content = delta?.content
                    if (!content.isNullOrEmpty()) {
                        emitEvent(AiStreamEvent.Content(content))
                    }
                    delta?.tool_calls?.forEach { toolCall ->
                        emitEvent(
                            AiStreamEvent.ToolCallDelta(
                                id = toolCall.id,
                                index = toolCall.index,
                                name = toolCall.function?.name,
                                argumentsDelta = toolCall.function?.arguments,
                                rawType = toolCall.type ?: "tool_call"
                            )
                        )
                    }
                }.getOrElse {
                    throw Exception("Invalid OpenAI chat stream chunk", it)
                }
            }
        } finally {
            response.close()
        }
    }

    private suspend fun fetchModelsInternal(provider: AiProviderConfig): List<AiAvailableModel> {
        require(provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank()) {
            "AI provider configuration incomplete: baseUrl and apiKey are required"
        }
        val keyRotator = KeyRotator(provider.apiKey)
        val modelsUrl = provider.modelsPath?.let { provider.baseUrl + it }
            ?: provider.modelsUrl
            ?: (provider.baseUrl + "/models")
        return retryWithBackoff(maxAttempts = 2, keyRotator = keyRotator) {
            val response = okHttpClient.newCallStrResponse {
                url(modelsUrl)
                addHeaders(
                    provider.headers + provider.customHeaders + mapOf(
                        "Authorization" to "Bearer ${keyRotator.currentKey}",
                        "Content-Type" to "application/json"
                    )
                )
            }
            if (!response.isSuccessful()) {
                throw AiHttpException(response.code(), response.message(), response.body)
            }
            val json = GSON.fromJson(response.body, OpenAiModelsResponse::class.java)
            json?.data.toAvailableModels()
        }
    }
}

/** 思考吃光输出预算后重试用的下限，太小的话关了思考照样被截断 */
private const val REASONING_FALLBACK_MAX_TOKENS = 8_192

/**
 * 「关闭思考」在 OpenAI 兼容协议里没有统一字段，[AiReasoningLevel.OFF] 之前实际发不出去：
 * `reasoningLevel.effortFor()` 只认 LOW..MAX，OFF 一律返回 null，于是思考保持服务端默认(开)。
 * 结果是思考型模型把 max_tokens 花在推理上、`content` 为空，调用方只能报
 * 「AI response contains only reasoning content」。
 *
 * 这里补上大多数网关认的 `enable_thinking`（通义/百炼、硅基流动、vLLM、ollama 等）。
 * OpenAI 与 DeepSeek 官方不认这个字段、且其推理模型本就不能关思考，所以不下发，免得 400。
 * GLM 走 [applyZhipuThinking] 的 `thinking.type`。
 */
internal fun MutableMap<String, Any?>.applyThinkingSwitch(
    provider: AiProviderConfig,
    reasoningLevel: AiReasoningLevel
) {
    if (reasoningLevel != AiReasoningLevel.OFF) return
    val identity = "${provider.id} ${provider.name} ${provider.baseUrl}".lowercase()
    val rejectsUnknownFields = "api.openai.com" in identity || "api.deepseek.com" in identity
    if (!rejectsUnknownFields) {
        this["enable_thinking"] = false
    }
}

/**
 * GLM models on Zhipu's Chat Completions API enable thinking by default.
 * Send the documented object form even when the model was added manually and
 * therefore has no reasoning capability metadata.
 */
internal fun MutableMap<String, Any?>.applyZhipuThinking(
    provider: AiProviderConfig,
    modelId: String,
    reasoningLevel: AiReasoningLevel
) {
    val identity = "${provider.id} ${provider.name} ${provider.baseUrl}".lowercase()
    val isZhipuProvider = "zhipu" in identity || "bigmodel" in identity
    val isGlmModel = modelId.lowercase().startsWith("glm-")
    if (isZhipuProvider || isGlmModel) {
        this["thinking"] = mapOf(
            "type" to if (reasoningLevel == AiReasoningLevel.OFF) "disabled" else "enabled"
        )
    }
}

/**
 * Chat Completions 协议下的供应商自带联网搜索。字段都不是 OpenAI 标准，只在识别出对应供应商
 * 且调用方显式请求联网时下发，其余供应商保持原样（乱发未知字段会被 400）。
 *
 * - 通义千问 / 阿里百炼：顶层 `enable_search`
 * - 智谱 GLM：追加 `web_search` 内置工具（与业务 function tools 共存）
 *
 * 供应商识别统一走 [nativeWebSearchSupport]，这里只负责按结果下发对应字段。
 *
 * 注意：开启后书名、作者名等提示词内容会被送去做网络检索。
 */
internal fun MutableMap<String, Any?>.applyProviderWebSearch(
    provider: AiProviderConfig,
    params: AiGenerationParams
) {
    if (!params.webSearch) return
    when (provider.nativeWebSearchSupport()) {
        AiNativeWebSearchSupport.QWEN_ENABLE_SEARCH -> {
            this["enable_search"] = true
        }

        AiNativeWebSearchSupport.ZHIPU_WEB_SEARCH_TOOL -> {
            // 智谱这几个开关文档给的是字符串 "True"，照抄以免网关按字面量校验
            appendServerTool(
                mapOf(
                    "type" to "web_search",
                    "web_search" to mapOf(
                        "enable" to "True",
                        "search_engine" to "search_std",
                        "search_result" to "True"
                    )
                )
            )
        }

        else -> Unit
    }
}

/** 把供应商内置工具追加到已有 `tools` 后面，不覆盖业务 function tools。 */
internal fun MutableMap<String, Any?>.appendServerTool(tool: Map<String, Any?>) {
    @Suppress("UNCHECKED_CAST")
    val existing = this["tools"] as? List<Map<String, Any?>> ?: emptyList()
    this["tools"] = existing + tool
}


// ---- Message & tool format converters ----

internal fun List<AiMessage>.toOpenAiChatMessages(): List<Map<String, Any?>> {
    return mapNotNull { message ->
        when {
            message.role == AiMessageRole.TOOL -> mapOf(
                "role" to "tool",
                "tool_call_id" to message.toolCallId,
                "content" to message.content
            )
            message.toolCalls.isNotEmpty() -> {
                buildMap {
                    put("role", "assistant")
                    put("content", message.content.takeIf { it.isNotBlank() })
                    put(
                        "tool_calls",
                        message.toolCalls.map {
                            mapOf(
                                "id" to it.id,
                                "type" to "function",
                                "function" to mapOf(
                                    "name" to it.name,
                                    "arguments" to it.arguments
                                )
                            )
                        }
                    )
                }
            }
            else -> mapOf("role" to message.role, "content" to message.content)
        }
    }
}

internal fun List<AiToolDefinition>.toOpenAiChatTools(): List<Map<String, Any?>> {
    return map {
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to it.name,
                "description" to it.description,
                "parameters" to it.inputSchema
            )
        )
    }
}

private fun List<OpenAiModelItem>?.toAvailableModels(): List<AiAvailableModel> {
    return orEmpty()
        .mapNotNull { item ->
            val id = item.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AiAvailableModel(
                id = id,
                name = item.display_name?.takeIf { it.isNotBlank() }
                    ?: item.displayName?.takeIf { it.isNotBlank() }
                    ?: item.name?.takeIf { it.isNotBlank() }
                    ?: id,
                contextWindow = item.context_window ?: item.contextWindow ?: 0,
                maxOutputTokens = item.max_tokens
                    ?: item.maxTokens
                    ?: item.max_output_tokens
                    ?: item.maxOutputTokens
                    ?: 0
            )
        }
        .distinctBy { it.id }
        .sortedBy { it.name.lowercase() }
}

// ---- Data classes ----

@Keep
internal data class OpenAiChatResponse(
    val choices: List<OpenAiChatChoice>?
)

@Keep
internal data class OpenAiChatChoice(
    val message: OpenAiChatMessage?
)

@Keep
internal data class OpenAiChatMessage(
    val content: String?,
    val reasoning_content: String?,
    val reasoning: String?
) {
    val reasoningContent: String?
        get() = reasoning_content ?: reasoning
}

@Keep
internal data class OpenAiModelsResponse(
    val data: List<OpenAiModelItem>?
)

@Keep
internal data class OpenAiModelItem(
    val id: String?,
    val name: String?,
    val display_name: String?,
    val displayName: String?,
    val context_window: Int?,
    val contextWindow: Int?,
    val max_tokens: Int?,
    val maxTokens: Int?,
    val max_output_tokens: Int?,
    val maxOutputTokens: Int?
)

@Keep
internal data class OpenAiChatStreamChunk(
    val choices: List<OpenAiChatStreamChoice>?
)

@Keep
internal data class OpenAiChatStreamChoice(
    val delta: OpenAiChatStreamDelta?
)

@Keep
internal data class OpenAiChatStreamDelta(
    val content: String?,
    val reasoning_content: String?,
    val reasoning: String?,
    val tool_calls: List<OpenAiChatToolCall>?
)

@Keep
internal data class OpenAiChatToolCall(
    val index: Int?,
    val id: String?,
    val type: String?,
    val function: OpenAiChatToolCallFunction?
)

@Keep
internal data class OpenAiChatToolCallFunction(
    val name: String?,
    val arguments: String?
)
