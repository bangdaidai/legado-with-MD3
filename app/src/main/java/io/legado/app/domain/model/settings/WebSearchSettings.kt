package io.legado.app.domain.model.settings

/**
 * AI 联网搜索配置。当前独立检索通道只接 Tavily。
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
    /**
     * 显式关闭"供应商内置联网"的供应商 ID 集合。缺省（不在集合里）= 跟随供应商能力自动开启：
     * 检测出支持内置联网的供应商（智谱/通义，见 AiNativeWebSearchSupport），其交互任务
     * （对话、人物识别等）自动联网；内容处理任务（翻译/摘要/净化）刻意不联网，避免版权正文外发。
     * 存关闭名单而不是开启名单，是为了老用户升级后默认行为不变、无需逐个去开。
     */
    val nativeWebSearchDisabledIds: Set<String> = emptySet(),
) {

    /** 未配置时调用方应完全跳过联网，而不是发一个注定失败的请求。 */
    val isConfigured: Boolean
        get() = enabled && apiKey.isNotBlank()

    /** 供应商内置联网是否对该供应商生效（能力检测由调用方另行判断）。 */
    fun isNativeWebSearchEnabled(providerId: String): Boolean =
        providerId !in nativeWebSearchDisabledIds

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
