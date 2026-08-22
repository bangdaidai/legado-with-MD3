package io.legado.app.domain.gateway

import io.legado.app.domain.model.settings.WebSearchSettings
import kotlinx.coroutines.flow.Flow

interface WebSearchSettingsGateway {
    val currentSettings: WebSearchSettings
    val settings: Flow<WebSearchSettings>
    suspend fun update(transform: (WebSearchSettings) -> WebSearchSettings)
}
