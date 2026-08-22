package io.legado.app.domain.model

/**
 * 一次联网检索请求。字段留空表示沿用
 * [io.legado.app.domain.model.settings.WebSearchSettings] 里的默认值。
 */
data class AiWebSearchQuery(
    val query: String,
    val topic: String? = null,
    val searchDepth: String? = null,
    val maxResults: Int? = null,
)

/** 单条检索结果。[content] 是搜索服务返回的正文片段，不是完整网页。 */
data class AiWebSearchHit(
    val title: String,
    val url: String,
    val content: String,
    val score: Double? = null,
)

/**
 * [answer] 是搜索服务自己生成的一句话总结，可能为空；不要当作可信来源直接引用，
 * 它同样是模型生成的。
 */
data class AiWebSearchResult(
    val query: String,
    val answer: String? = null,
    val hits: List<AiWebSearchHit> = emptyList(),
) {
    val isEmpty: Boolean
        get() = answer.isNullOrBlank() && hits.isEmpty()
}
