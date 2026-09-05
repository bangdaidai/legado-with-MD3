package io.legado.app.domain.gateway

import io.legado.app.data.entities.AiModelProfile
import io.legado.app.data.entities.AiProviderProfile
import io.legado.app.data.entities.AiTaskPreset
import io.legado.app.domain.model.AiAvailableModel
import io.legado.app.domain.model.AiModelDraft
import io.legado.app.domain.model.AiProfileDraft
import io.legado.app.domain.model.AiProviderDraft
import io.legado.app.domain.model.AiTaskPresetConfig
import kotlinx.coroutines.flow.Flow

interface AiProfileGateway {
    fun observeProviders(): Flow<List<AiProviderProfile>>
    fun observeModels(): Flow<List<AiModelProfile>>
    fun observePresets(): Flow<List<AiTaskPreset>>
    suspend fun getProvider(id: String): AiProviderProfile?
    suspend fun getModel(id: String): AiModelProfile?
    suspend fun getTaskPreset(taskType: String): AiTaskPresetConfig?
    suspend fun getProviderApiKey(providerId: String): String
    suspend fun saveProvider(draft: AiProviderDraft): AiProviderProfile
    suspend fun saveModel(draft: AiModelDraft): AiModelProfile
    suspend fun importProviderModels(providerId: String, models: List<AiAvailableModel>): List<AiModelProfile>
    suspend fun setDefaultModel(modelProfileId: String): AiTaskPresetConfig
    suspend fun setTaskPresetModel(taskType: String, modelProfileId: String): AiTaskPresetConfig
    suspend fun saveDefaultChatProfile(draft: AiProfileDraft): AiTaskPresetConfig
    suspend fun saveTaskPreset(
        taskType: String,
        promptTemplate: String,
        temperature: Float,
        maxOutputTokens: Int
    ): AiTaskPresetConfig

    /**
     * Skill 导入 / 新建专用：按给定实体原样落库（不套用默认预设的补全逻辑）。
     * modelProfileId 可为空串，代表「待绑定模型」，运行时会跳过该预设。
     */
    suspend fun saveImportedTaskPreset(preset: AiTaskPreset)
    suspend fun deleteTaskPreset(presetId: String)

    /** 把指定预设绑定为给定模型；模型不存在时抛错 */
    suspend fun bindTaskPresetModel(presetId: String, modelProfileId: String)

    /** 把指定预设设为其任务类型的默认预设，同任务类型的其它预设取消默认 */
    suspend fun setDefaultTaskPreset(presetId: String)
    suspend fun deleteProvider(providerId: String)
    suspend fun deleteModel(modelId: String)
}
