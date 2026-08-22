package io.legado.app.domain.model

/**
 * 供应商自带联网搜索的支持情况。
 *
 * 这份判断原先分散在三个 protocol handler 各自的 `apply*WebSearch` 里。
 * [io.legado.app.domain.usecase.GenerateAuthorBioUseCase] 需要知道"这个供应商到底会不会联网"
 * 才能决定要不要用 Tavily 兜底，所以收敛到这里作为单一真源：
 * **能不能**由这里回答，**怎么下发**仍归各 handler。
 */
enum class AiNativeWebSearchSupport {
    /** 顶层 `enable_search`（通义千问 / 阿里百炼的 Chat Completions 端点）。 */
    QWEN_ENABLE_SEARCH,

    /** 追加 `web_search` 内置工具（智谱 GLM）。 */
    ZHIPU_WEB_SEARCH_TOOL,

    /** Responses 协议的 `{"type":"web_search"}`。 */
    OPENAI_RESPONSES_WEB_SEARCH,

    /** Anthropic 的 `web_search_20250305`。 */
    ANTHROPIC_WEB_SEARCH,

    /**
     * 不支持。典型是 DeepSeek、小米 MiMo 和用户自填的通用 OpenAI 兼容端点 ——
     * 下发任何联网字段都会被 400 或静默忽略。
     */
    NONE;

    val isSupported: Boolean
        get() = this != NONE
}

/**
 * 供应商识别只能靠 id / 名称 / baseUrl 的子串匹配 —— 各家没有可探测的能力声明。
 * 新增供应商时改这里一处即可，不要再回到 handler 里加字符串判断。
 */
fun AiProviderConfig.nativeWebSearchSupport(): AiNativeWebSearchSupport = when (protocol) {
    AiProtocol.ANTHROPIC_MESSAGES -> AiNativeWebSearchSupport.ANTHROPIC_WEB_SEARCH
    AiProtocol.OPENAI_RESPONSES -> AiNativeWebSearchSupport.OPENAI_RESPONSES_WEB_SEARCH
    AiProtocol.OPENAI_CHAT_COMPLETIONS -> chatCompletionsWebSearchSupport()
    else -> AiNativeWebSearchSupport.NONE
}

private fun AiProviderConfig.chatCompletionsWebSearchSupport(): AiNativeWebSearchSupport {
    val identity = "$id $name $baseUrl".lowercase()
    return when {
        QWEN_MARKERS.any { it in identity } -> AiNativeWebSearchSupport.QWEN_ENABLE_SEARCH
        ZHIPU_MARKERS.any { it in identity } -> AiNativeWebSearchSupport.ZHIPU_WEB_SEARCH_TOOL
        else -> AiNativeWebSearchSupport.NONE
    }
}

private val QWEN_MARKERS = listOf("dashscope", "qwen", "bailian")
private val ZHIPU_MARKERS = listOf("zhipu", "bigmodel")
