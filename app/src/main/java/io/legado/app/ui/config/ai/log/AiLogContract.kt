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
    val provider: String,
    val model: String,
    val summary: String,
    val success: Boolean,
    val durationText: String,
    val error: String?,
    val steps: List<AiLogStepUi> = emptyList(),
)

sealed interface AiLogIntent {
    data object Refresh : AiLogIntent
    data object Clear : AiLogIntent
    data object CopyAll : AiLogIntent
}

sealed interface AiLogEffect {
    data class ShowMessage(val message: String) : AiLogEffect
}
