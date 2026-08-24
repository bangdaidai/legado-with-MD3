package io.legado.app.ui.config.protagonistExtractionConfig

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.domain.gateway.ProtagonistExtractionSettingsGateway
import io.legado.app.domain.model.settings.ProtagonistExtractionSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProtagonistExtractionConfigViewModel(
    private val gateway: ProtagonistExtractionSettingsGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ProtagonistExtractionConfigUiState(gateway.currentSettings),
    )
    val uiState = _uiState

    private val _effects = MutableSharedFlow<ProtagonistExtractionConfigEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<ProtagonistExtractionConfigEffect> = _effects.asSharedFlow()

    fun onIntent(intent: ProtagonistExtractionConfigIntent) {
        when (intent) {
            is ProtagonistExtractionConfigIntent.SetProtagonistPrefix ->
                persist { it.copy(protagonistPrefix = intent.value) }

            is ProtagonistExtractionConfigIntent.SetSupportingPrefix ->
                persist { it.copy(supportingPrefix = intent.value) }

            is ProtagonistExtractionConfigIntent.SetSeparators ->
                persist { it.copy(separators = intent.value) }

            is ProtagonistExtractionConfigIntent.SetMinLength -> {
                val value = intent.value.toIntOrNull() ?: return
                persist { it.copy(minLength = value) }
            }

            is ProtagonistExtractionConfigIntent.SetMaxLength -> {
                val value = intent.value.toIntOrNull() ?: return
                persist { it.copy(maxLength = value) }
            }

            is ProtagonistExtractionConfigIntent.SetInvalidWords ->
                persist { it.copy(invalidWords = intent.value) }

            is ProtagonistExtractionConfigIntent.SetRelaxed ->
                persist { it.copy(relaxedFirstLine = intent.value) }

            is ProtagonistExtractionConfigIntent.RestoreDefaults ->
                persist { ProtagonistExtractionSettings.DEFAULT }
        }
    }

    private fun persist(transform: (ProtagonistExtractionSettings) -> ProtagonistExtractionSettings) {
        val next = transform(_uiState.value.settings)
        _uiState.update { it.copy(settings = next) }
        viewModelScope.launch {
            runCatching { gateway.update { next } }
                .onFailure { _effects.tryEmit(ProtagonistExtractionConfigEffect.ShowToast(it.localizedMessage.orEmpty())) }
        }
    }
}
