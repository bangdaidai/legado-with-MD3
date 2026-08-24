package io.legado.app.domain.gateway

import io.legado.app.domain.model.settings.ProtagonistExtractionSettings
import kotlinx.coroutines.flow.Flow

interface ProtagonistExtractionSettingsGateway {
    val currentSettings: ProtagonistExtractionSettings
    val settings: Flow<ProtagonistExtractionSettings>
    suspend fun update(transform: (ProtagonistExtractionSettings) -> ProtagonistExtractionSettings)
}
