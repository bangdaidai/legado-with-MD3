package io.legado.app.domain.gateway

import io.legado.app.domain.model.AiWebSearchQuery
import io.legado.app.domain.model.AiWebSearchResult

/**
 * 与 LLM 供应商无关的联网检索通道。
 *
 * 之所以需要它：[io.legado.app.domain.model.AiGenerationParams.webSearch] 走的是供应商自带的
 * 联网能力，覆盖面见 [io.legado.app.domain.model.nativeWebSearchSupport]；对不在其中的供应商
 * （DeepSeek、MiMo、用户自填的通用兼容端点）那个开关静默无效。这条通道对所有模型都成立。
 *
 * 因此调用方应先判断供应商有没有原生联网，只在
 * [io.legado.app.domain.model.AiNativeWebSearchSupport.NONE] 时用这里兜底，
 * 否则会检索两次、付两份钱。
 *
 * 注意：调用即把 query 发往第三方搜索服务。调用方必须先检查 [isConfigured]，
 * 未配置时应完全跳过联网，而不是发一个注定失败的请求。
 */
interface AiWebSearchGateway {

    /** 用户已显式开启且填了 API key。 */
    val isConfigured: Boolean

    suspend fun search(query: AiWebSearchQuery): Result<AiWebSearchResult>
}
