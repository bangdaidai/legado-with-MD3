package io.legado.app.domain.usecase

import com.google.gson.Gson
import io.legado.app.data.entities.AiModelProfile
import io.legado.app.data.entities.AiPromptPreset
import io.legado.app.data.entities.AiProviderProfile
import io.legado.app.data.entities.AiTaskPreset
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.gateway.AiPromptPresetGateway
import io.legado.app.domain.model.AiGenerationParams
import io.legado.app.domain.model.AiSkillImportConflict
import io.legado.app.domain.model.AiSkillImportItem
import io.legado.app.domain.model.AiSkillImportPreview
import io.legado.app.domain.model.AiSkillImportResult
import io.legado.app.domain.model.AiSkillModelMatch
import io.legado.app.domain.model.AiSkillModelMatchStatus
import io.legado.app.domain.model.AiSkillModelRef
import io.legado.app.domain.model.AiSkillPack
import io.legado.app.domain.model.AiSkillPackCodec
import io.legado.app.domain.model.AiSkillPackDecodeResult
import io.legado.app.domain.model.AiSkillPackItem
import io.legado.app.domain.model.AiTaskRuntimeOptions
import io.legado.app.domain.model.AiTaskType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/**
 * Skill（任务预设 / 指令预设）的导出、导入解析与提交。
 *
 * 导入遵循「逐条准入」原则：单条不兼容只跳过该条，不阻断整包；
 * 导入的预设一律不成为默认预设，避免覆盖用户当前的工作流。
 */
class AiSkillPackUseCase(
    private val aiProfileGateway: AiProfileGateway,
    private val aiPromptPresetGateway: AiPromptPresetGateway,
) {

    private val gson = Gson()

    /** 导出为可分享 JSON；模型只带「供应商名 + 模型名」引用，缺失时 modelRef 为 null */
    suspend fun exportPack(
        taskPresets: List<AiTaskPreset>,
        promptPresets: List<AiPromptPreset>,
    ): String = withContext(Dispatchers.IO) {
        val models = aiProfileGateway.observeModels().firstOrNull().orEmpty().associateBy { it.id }
        val providers = aiProfileGateway.observeProviders().firstOrNull().orEmpty().associateBy { it.id }
        val items = buildList {
            taskPresets.forEach { preset ->
                val model = models[preset.modelProfileId]
                val provider = model?.let { providers[it.providerId] }
                add(
                    AiSkillPackItem(
                        type = AiSkillPackCodec.TYPE_TASK_PRESET,
                        taskType = preset.taskType,
                        name = preset.name,
                        promptTemplate = preset.promptTemplate,
                        params = AiSkillPackCodec.parseParamsJson(preset.paramsJson) ?: AiGenerationParams(),
                        runtimeOptions = AiSkillPackCodec.parseRuntimeOptionsJson(preset.chunkPolicyJson),
                        modelRef = model?.let { m ->
                            provider?.let { p ->
                                AiSkillModelRef(
                                    providerName = p.name,
                                    protocol = p.protocol,
                                    modelName = m.displayName,
                                    modelId = m.modelId
                                )
                            }
                        }
                    )
                )
            }
            promptPresets.forEach { preset ->
                add(
                    AiSkillPackItem(
                        type = AiSkillPackCodec.TYPE_PROMPT_PRESET,
                        taskType = preset.taskType,
                        name = preset.name,
                        instruction = preset.instruction
                    )
                )
            }
        }
        val firstName = items.firstOrNull()?.name.orEmpty()
        val packName = if (items.size > 1) "$firstName 等 ${items.size} 条" else firstName
        AiSkillPackCodec.encode(
            AiSkillPack(name = packName, description = "", items = items)
        )
    }

    /** 解析分享来的文本并生成逐条预览；解析失败返回可展示的原因 */
    suspend fun parseImport(text: String): Result<AiSkillImportPreview> = withContext(Dispatchers.IO) {
        runCatching {
            val pack = when (val decoded = AiSkillPackCodec.decode(text)) {
                is AiSkillPackDecodeResult.Success -> decoded.pack
                is AiSkillPackDecodeResult.Failure -> error(decoded.reason)
            }
            val models = aiProfileGateway.observeModels().firstOrNull().orEmpty()
            val providers = aiProfileGateway.observeProviders().firstOrNull().orEmpty()
            val existingTaskPresets = aiProfileGateway.observePresets().firstOrNull().orEmpty()
            val existingPromptPresets = aiPromptPresetGateway.getAll()
            val items = pack.items.mapIndexed { index, item ->
                AiSkillImportItem(
                    index = index,
                    type = item.type,
                    taskType = item.taskType,
                    name = item.name,
                    prompt = item.promptTemplate.ifBlank { item.instruction },
                    paramsSummary = buildParamsSummary(item),
                    modelMatch = matchModelRef(item.modelRef, models, providers),
                    conflict = findConflict(item, existingTaskPresets, existingPromptPresets)
                )
            }
            AiSkillImportPreview(pack = pack, items = items)
        }
    }

    /** 落库选中的条目。模型匹配在提交时重算，保证与当前配置一致 */
    suspend fun commitImport(
        pack: AiSkillPack,
        selectedIndexes: Set<Int>,
    ): Result<AiSkillImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val models = aiProfileGateway.observeModels().firstOrNull().orEmpty()
            val providers = aiProfileGateway.observeProviders().firstOrNull().orEmpty()
            val now = System.currentTimeMillis()
            var imported = 0
            var skipped = 0
            pack.items.forEachIndexed { index, item ->
                if (index !in selectedIndexes) {
                    skipped++
                    return@forEachIndexed
                }
                when (item.type) {
                    AiSkillPackCodec.TYPE_TASK_PRESET -> {
                        aiProfileGateway.saveImportedTaskPreset(
                            AiTaskPreset(
                                id = newId("preset"),
                                taskType = item.taskType,
                                name = item.name,
                                modelProfileId = matchModelRef(item.modelRef, models, providers)
                                    .modelProfileId.orEmpty(),
                                promptTemplate = item.promptTemplate,
                                paramsJson = gson.toJson(item.params ?: AiGenerationParams()),
                                chunkPolicyJson = item.runtimeOptions?.let { gson.toJson(it) },
                                enabled = true,
                                // 导入的预设永不自动成为默认，避免覆盖用户当前工作流
                                isDefault = false,
                                sortNumber = 0,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                        imported++
                    }

                    AiSkillPackCodec.TYPE_PROMPT_PRESET -> {
                        aiPromptPresetGateway.savePreset(
                            AiPromptPreset(
                                id = newId("prompt_preset"),
                                taskType = item.taskType,
                                name = item.name,
                                instruction = item.instruction,
                                enabled = true,
                                builtIn = false,
                                sortNumber = 0,
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                        imported++
                    }

                    else -> skipped++
                }
            }
            AiSkillImportResult(imported = imported, skipped = skipped)
        }
    }

    /** 模型匹配：精确（供应商名+模型名）→ 宽松（仅模型名唯一）→ 匹配不到则待绑定 */
    private fun matchModelRef(
        ref: AiSkillModelRef?,
        models: List<AiModelProfile>,
        providers: List<AiProviderProfile>,
    ): AiSkillModelMatch {
        ref ?: return AiSkillModelMatch.missing(null)
        val byModelName = models.filter {
            it.modelId == ref.modelId || it.displayName == ref.modelName
        }
        val exact = byModelName.filter { model ->
            providers.any { it.id == model.providerId && it.name == ref.providerName }
        }
        val chosen = when {
            exact.size == 1 -> exact.single()
            exact.isEmpty() && byModelName.size == 1 -> byModelName.single()
            // 0 个或多个候选都宁可放弃，落到待绑定态由用户手动指定
            else -> null
        } ?: return AiSkillModelMatch.missing(ref)
        val provider = providers.firstOrNull { it.id == chosen.providerId }
        return AiSkillModelMatch(
            status = AiSkillModelMatchStatus.MATCHED,
            modelProfileId = chosen.id,
            providerName = provider?.name.orEmpty(),
            modelName = chosen.displayName
        )
    }

    private fun findConflict(
        item: AiSkillPackItem,
        existingTaskPresets: List<AiTaskPreset>,
        existingPromptPresets: List<AiPromptPreset>,
    ): AiSkillImportConflict {
        val sameContent = if (item.type == AiSkillPackCodec.TYPE_TASK_PRESET) {
            existingTaskPresets.firstOrNull {
                it.taskType == item.taskType && it.promptTemplate == item.promptTemplate
            }
        } else {
            existingPromptPresets.firstOrNull {
                it.taskType == item.taskType && it.instruction == item.instruction
            }
        }
        sameContent?.let {
            return AiSkillImportConflict(existingPresetId = it.id, sameContent = true)
        }
        val sameName = if (item.type == AiSkillPackCodec.TYPE_TASK_PRESET) {
            existingTaskPresets.firstOrNull { it.taskType == item.taskType && it.name == item.name }
        } else {
            existingPromptPresets.firstOrNull { it.taskType == item.taskType && it.name == item.name }
        }
        return AiSkillImportConflict(existingPresetId = sameName?.id, sameContent = false)
    }

    private fun buildParamsSummary(
        item: AiSkillPackItem,
    ): String {
        val parts = mutableListOf<String>()
        item.params?.let { params ->
            params.temperature?.let { parts.add("temperature=$it") }
            params.maxOutputTokens?.let { parts.add("maxTokens=$it") }
            if (params.webSearch) parts.add("webSearch")
        }
        item.runtimeOptions?.let { options ->
            if (options.targetLanguage != AiTaskRuntimeOptions.DEFAULT_TARGET_LANGUAGE) {
                parts.add("lang=${options.targetLanguage}")
            }
            if (options.concurrentRequests != AiTaskRuntimeOptions.DEFAULT_CONCURRENT_REQUESTS) {
                parts.add("concurrency=${options.concurrentRequests}")
            }
        }
        return parts.joinToString(", ")
    }

    private fun newId(prefix: String): String =
        "${prefix}_${Uuid.random().toString().replace("-", "")}"
}
