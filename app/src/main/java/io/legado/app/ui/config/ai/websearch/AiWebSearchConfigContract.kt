package io.legado.app.ui.config.ai.websearch

import androidx.compose.runtime.Stable
import io.legado.app.domain.model.settings.WebSearchSettings

@Stable
data class AiWebSearchConfigUiState(
    val settings: WebSearchSettings = WebSearchSettings(),
    /** 测试检索进行中；期间禁掉测试入口，避免重复消耗额度。 */
    val testing: Boolean = false,
)

sealed interface AiWebSearchConfigIntent {
    data class SetEnabled(val enabled: Boolean) : AiWebSearchConfigIntent
    data class SetApiKey(val apiKey: String) : AiWebSearchConfigIntent
    data class SetBaseUrl(val baseUrl: String) : AiWebSearchConfigIntent
    data class SetTopic(val topic: String) : AiWebSearchConfigIntent
    data class SetSearchDepth(val searchDepth: String) : AiWebSearchConfigIntent
    data class SetMaxResults(val maxResults: Int) : AiWebSearchConfigIntent
    data object RunTestSearch : AiWebSearchConfigIntent
}

sealed interface AiWebSearchConfigEffect {
    data class ShowMessage(val message: String) : AiWebSearchConfigEffect
}
