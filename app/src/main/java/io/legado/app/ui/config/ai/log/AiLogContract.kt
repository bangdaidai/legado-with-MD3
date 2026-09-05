package io.legado.app.ui.config.ai.log

import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class AiLogUiState(
    val logs: ImmutableList<AiLogItemUi> = persistentListOf(),
    val enabled: Boolean = false,
    val loading: Boolean = true,
)

@Stable
data class AiLogStepUi(
    val relativeMs: Long,
    val label: String,
)

@Stable
data class AiLogItemUi(
    val timeText: String,
    val kind: String,
    val scenario: String,
    val provider: String,
    val model: String,
    val summary: String,
    val success: Boolean,
    val durationText: String,
    val error: String?,
    val steps: List<AiLogStepUi> = emptyList(),
    /** 实际发给模型的全部消息，供对照优化提示词；空串表示该记录没有（如拉取模型）。 */
    val prompt: String = "",
    /** 模型思考内容；空串表示模型没有输出思考。 */
    val reasoning: String = "",
    /** 模型最终输出正文。 */
    val output: String = "",
)

sealed interface AiLogIntent {
    data object Refresh : AiLogIntent
    data object Clear : AiLogIntent
    data object CopyAll : AiLogIntent
    data class CopyItem(val item: AiLogItemUi) : AiLogIntent
}

sealed interface AiLogEffect {
    data class ShowMessage(val message: String) : AiLogEffect
}
