package io.legado.app.ui.config.protagonistExtractionConfig

import io.legado.app.domain.model.settings.ProtagonistExtractionSettings

data class ProtagonistExtractionConfigUiState(
    val settings: ProtagonistExtractionSettings,
)

sealed interface ProtagonistExtractionConfigIntent {
    data class SetProtagonistPrefix(val value: String) : ProtagonistExtractionConfigIntent
    data class SetSupportingPrefix(val value: String) : ProtagonistExtractionConfigIntent
    data class SetSeparators(val value: String) : ProtagonistExtractionConfigIntent
    data class SetMinLength(val value: String) : ProtagonistExtractionConfigIntent
    data class SetMaxLength(val value: String) : ProtagonistExtractionConfigIntent
    data class SetInvalidWords(val value: String) : ProtagonistExtractionConfigIntent
    data class SetRelaxed(val value: Boolean) : ProtagonistExtractionConfigIntent
    data object RestoreDefaults : ProtagonistExtractionConfigIntent
}

sealed interface ProtagonistExtractionConfigEffect {
    data class ShowToast(val message: String) : ProtagonistExtractionConfigEffect
}
