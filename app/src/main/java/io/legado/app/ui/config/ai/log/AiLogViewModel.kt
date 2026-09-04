package io.legado.app.ui.config.ai.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.repository.ai.AiLogRepository
import io.legado.app.help.config.AppConfigStore
import io.legado.app.utils.sendToClip
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AiLogViewModel(
    private val aiLogRepository: AiLogRepository,
) : ViewModel() {

    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    private val _uiState = MutableStateFlow(AiLogUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<AiLogEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            AppConfigStore.observeBoolean(PreferKey.aiLogEnabled).collect { enabled ->
                _uiState.update { it.copy(enabled = enabled ?: false) }
            }
        }
        refresh()
    }

    fun onIntent(intent: AiLogIntent) {
        when (intent) {
            is AiLogIntent.Refresh -> refresh()
            is AiLogIntent.Clear -> clear()
            is AiLogIntent.CopyAll -> copyAll()
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            val logs = aiLogRepository.getLogs().map { entry ->
                AiLogItemUi(
                    timeText = timeFormat.format(Date(entry.timeMillis)),
                    kind = kindLabel(entry.kind),
                    scenario = entry.scenario ?: kindLabel(entry.kind),
                    provider = entry.providerName ?: entry.providerProtocol ?: "-",
                    model = entry.modelDisplayName ?: entry.modelId ?: "-",
                    summary = entry.summary,
                    success = entry.success,
                    durationText = formatDuration(entry.durationMillis),
                    error = entry.error,
                    steps = entry.steps.map { AiLogStepUi(relativeMs = it.relativeMs, label = it.label) },
                    prompt = entry.prompt.orEmpty(),
                    reasoning = entry.reasoning.orEmpty(),
                    output = entry.output.orEmpty(),
                )
            }.toImmutableList()
            _uiState.update { it.copy(logs = logs, loading = false) }
        }
    }

    private fun clear() {
        viewModelScope.launch {
            aiLogRepository.clear()
            refresh()
            _effects.tryEmit(AiLogEffect.ShowMessage(appCtx.getString(R.string.ai_log_cleared)))
        }
    }

    private fun copyAll() {
        val state = _uiState.value
        if (state.logs.isEmpty()) {
            _effects.tryEmit(AiLogEffect.ShowMessage(appCtx.getString(R.string.ai_log_empty)))
            return
        }
        val text = state.logs.joinToString("\n\n") { item ->
            buildString {
                append("[${item.timeText}] ${item.scenario} · ${item.kind} ${if (item.success) "成功" else "失败"}")
                append(" | ${item.provider} / ${item.model}")
                append(" | ${item.durationText}")
                if (item.summary.isNotBlank()) append("\n${item.summary}")
                if (item.prompt.isNotBlank()) append("\n提示词:\n${item.prompt}")
                if (item.reasoning.isNotBlank()) append("\n思考:\n${item.reasoning}")
                if (item.output.isNotBlank()) append("\n输出:\n${item.output}")
                if (item.steps.isNotEmpty()) {
                    append("\n过程:")
                    item.steps.forEach { step ->
                        append("\n  +${formatDuration(step.relativeMs)} ${step.label}")
                    }
                }
                if (!item.success && !item.error.isNullOrBlank()) append("\n错误: ${item.error}")
            }
        }
        appCtx.sendToClip(text)
    }

    private fun kindLabel(kind: String): String = when (kind) {
        "generate" -> "生成"
        "generateStream" -> "流式生成"
        "fetchModels" -> "拉取模型"
        "webSearch" -> "联网检索"
        "cloudTts" -> "云端语音合成"
        "cloudTtsVoices" -> "云端语音音色"
        else -> kind
    }

    private fun formatDuration(millis: Long): String = when {
        millis < 1000 -> "${millis}ms"
        else -> String.format(Locale.getDefault(), "%.1fs", millis / 1000.0)
    }
}
