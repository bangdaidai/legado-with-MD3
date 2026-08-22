package io.legado.app.ui.config.ai.websearch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.domain.gateway.AiWebSearchGateway
import io.legado.app.domain.gateway.WebSearchSettingsGateway
import io.legado.app.domain.model.AiWebSearchQuery
import io.legado.app.domain.model.settings.WebSearchSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import splitties.init.appCtx

class AiWebSearchConfigViewModel(
    private val settingsGateway: WebSearchSettingsGateway,
    private val aiWebSearchGateway: AiWebSearchGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AiWebSearchConfigUiState(settings = settingsGateway.currentSettings)
    )
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<AiWebSearchConfigEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            settingsGateway.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    fun onIntent(intent: AiWebSearchConfigIntent) {
        when (intent) {
            is AiWebSearchConfigIntent.SetEnabled ->
                update { it.copy(enabled = intent.enabled) }

            is AiWebSearchConfigIntent.SetApiKey ->
                update { it.copy(apiKey = intent.apiKey.trim()) }

            is AiWebSearchConfigIntent.SetBaseUrl -> {
                // 清空视为恢复默认，否则 URL 变空会让每次检索都失败。
                val url = intent.baseUrl.trim().ifBlank { WebSearchSettings.DEFAULT_BASE_URL }
                update { it.copy(baseUrl = url) }
            }

            is AiWebSearchConfigIntent.SetTopic ->
                update { it.copy(topic = intent.topic) }

            is AiWebSearchConfigIntent.SetSearchDepth ->
                update { it.copy(searchDepth = intent.searchDepth) }

            is AiWebSearchConfigIntent.SetMaxResults -> {
                val count = intent.maxResults
                    .coerceIn(WebSearchSettings.MIN_RESULTS, WebSearchSettings.MAX_RESULTS)
                update { it.copy(maxResults = count) }
            }

            AiWebSearchConfigIntent.RunTestSearch -> runTestSearch()
        }
    }

    private fun update(transform: (WebSearchSettings) -> WebSearchSettings) {
        viewModelScope.launch { settingsGateway.update(transform) }
    }

    /**
     * 真发一次请求。密钥和地址是否可用没法离线判断，只有打一次才知道，
     * 所以这里不做假校验，直接把服务端的失败原因显示出来。
     */
    private fun runTestSearch() {
        if (_uiState.value.testing) return
        if (!aiWebSearchGateway.isConfigured) {
            emitMessage(appCtx.getString(R.string.ai_web_search_key_required))
            return
        }
        _uiState.update { it.copy(testing = true) }
        viewModelScope.launch {
            val message = aiWebSearchGateway.search(AiWebSearchQuery(query = TEST_QUERY)).fold(
                onSuccess = { result ->
                    if (result.isEmpty) {
                        appCtx.getString(R.string.ai_web_search_test_empty)
                    } else {
                        appCtx.getString(R.string.ai_web_search_test_success, result.hits.size)
                    }
                },
                onFailure = { error ->
                    appCtx.getString(
                        R.string.ai_web_search_test_failed,
                        error.localizedMessage ?: error::class.java.simpleName
                    )
                }
            )
            _uiState.update { it.copy(testing = false) }
            emitMessage(message)
        }
    }

    private fun emitMessage(message: String) {
        _effects.tryEmit(AiWebSearchConfigEffect.ShowMessage(message))
    }

    private companion object {
        /** 固定的中性关键词，测试时不带用户书架信息出去。 */
        const val TEST_QUERY = "legado reader"
    }
}
