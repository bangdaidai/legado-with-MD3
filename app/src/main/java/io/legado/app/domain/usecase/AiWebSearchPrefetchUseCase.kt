package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.AiStreamEvent
import io.legado.app.domain.gateway.AiTextGateway
import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiGenerationParams
import io.legado.app.domain.model.AiMessage
import io.legado.app.domain.model.AiMessageRole
import io.legado.app.domain.model.AiModelConfig

/**
 * 供应商内置联网的预检索：不带任何 function 工具发一轮纯对话，让内置 web_search 触发。
 *
 * 为什么存在：智谱实测（2026-09，glm-4.7-flash / glm-4.6）内置联网与 function 工具调用互斥——
 * 请求里只要带 function tools，内置联网就静默不执行，强制 search_query 也无效；纯对话请求才会
 * 触发。搜索结果按 token 计费（免费模型零成本），不消耗按次的搜索资源包。调用方把返回文本作为
 * 上下文注入带工具的主请求，主请求里的 function 工具循环不受影响。
 *
 * [query] 必须是疑问式表述——智谱的搜索意图识别对指令式文案不触发搜索。
 */
class AiWebSearchPrefetchUseCase(
    private val aiTextGateway: AiTextGateway,
) {

    suspend fun prefetch(model: AiModelConfig, query: String): String? {
        if (query.isBlank()) return null
        val request = AiGenerateRequest(
            model = model,
            messages = listOf(AiMessage(AiMessageRole.USER, query)),
            params = AiGenerationParams(webSearch = true),
        )
        val output = StringBuilder()
        aiTextGateway.generateStream(request).collect { event ->
            if (event is AiStreamEvent.Content) output.append(event.text)
        }
        return output.toString().trim().takeIf { it.isNotEmpty() }?.take(MAX_CHARS)
    }

    companion object {
        const val MAX_CHARS = 8000
    }
}
