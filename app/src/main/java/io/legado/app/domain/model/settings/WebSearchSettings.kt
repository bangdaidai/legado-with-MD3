package io.legado.app.domain.model.settings

/**
 * AI 联网搜索配置。当前只接 Tavily。
 *
 * 与 [io.legado.app.domain.model.AiGenerationParams.webSearch] 的区别：后者是"请求供应商自带
 * 的联网能力"，只有通义千问系会真的生效；这里是与供应商无关的独立检索通道，任何模型都能用。
 */
data class WebSearchSettings(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val topic: String = TOPIC_GENERAL,
    val searchDepth: String = DEPTH_BASIC,
    val maxResults: Int = 5,
) {

    /** 未配置时调用方应完全跳过联网，而不是发一个注定失败的请求。 */
    val isConfigured: Boolean
        get() = enabled && apiKey.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.tavily.com"

        const val TOPIC_GENERAL = "general"
        const val TOPIC_NEWS = "news"
        const val TOPIC_FINANCE = "finance"

        const val DEPTH_BASIC = "basic"
        const val DEPTH_ADVANCED = "advanced"

        const val MIN_RESULTS = 1
        const val MAX_RESULTS = 10

        val topics = listOf(TOPIC_GENERAL, TOPIC_NEWS, TOPIC_FINANCE)
        val depths = listOf(DEPTH_BASIC, DEPTH_ADVANCED)
    }
}
