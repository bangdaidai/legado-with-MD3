package io.legado.app.ui.config.ai.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.data.entities.AiModelProfile
import io.legado.app.data.entities.AiPromptPreset
import io.legado.app.data.entities.AiProviderProfile
import io.legado.app.data.entities.AiTaskPreset
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.gateway.AiPromptPresetGateway
import io.legado.app.domain.model.AiGenerationParams
import io.legado.app.domain.model.AiSkillModelMatchStatus
import io.legado.app.domain.model.AiSkillPack
import io.legado.app.domain.model.AiTaskType
import io.legado.app.domain.model.aiTaskSceneLabel
import io.legado.app.domain.usecase.AiSkillPackUseCase
import io.legado.app.utils.GSON
import io.legado.app.utils.toastOnUi
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.util.UUID

class SkillsViewModel(
    private val aiProfileGateway: AiProfileGateway,
    private val aiPromptPresetGateway: AiPromptPresetGateway,
    private val aiSkillPackUseCase: AiSkillPackUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SkillsUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SkillsEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    /** 指令预设表没有 Flow 查询，导入 / 删除后手动触发重查 */
    private val promptPresetRefresh = MutableStateFlow(0)

    // 导出 / 删除等按 id 操作时用的内存快照
    private var taskPresets: List<AiTaskPreset> = emptyList()
    private var promptPresets: List<AiPromptPreset> = emptyList()
    private var pendingImportPack: AiSkillPack? = null

    private data class ProfileSnapshot(
        val taskPresets: List<AiTaskPreset>,
        val models: List<AiModelProfile>,
        val providers: List<AiProviderProfile>,
        val promptPresets: List<AiPromptPreset>
    )

    init {
        viewModelScope.launch {
            combine(
                aiProfileGateway.observePresets(),
                aiProfileGateway.observeModels(),
                aiProfileGateway.observeProviders(),
                promptPresetRefresh
            ) { presets, models, providers, _ ->
                ProfileSnapshot(presets, models, providers, emptyList())
            }.collect { snapshot ->
                val prompts = aiPromptPresetGateway.getAll()
                submitSnapshot(snapshot.copy(promptPresets = prompts))
            }
        }
    }

    fun onIntent(intent: SkillsIntent) {
        when (intent) {
            is SkillsIntent.ExportTaskPreset -> exportTaskPreset(intent.presetId)
            is SkillsIntent.ExportPromptPreset -> exportPromptPreset(intent.presetId)
            is SkillsIntent.ParseImport -> parseImport(intent.text)
            SkillsIntent.DismissImport -> clearImport()
            is SkillsIntent.ToggleImportItem -> toggleImportItem(intent.index)
            SkillsIntent.ConfirmImport -> confirmImport()
            is SkillsIntent.DeleteTaskPreset -> deleteTaskPreset(intent.presetId)
            is SkillsIntent.DeletePromptPreset -> deletePromptPreset(intent.presetId)
            is SkillsIntent.BindTaskPresetModel -> bindTaskPresetModel(
                intent.presetId,
                intent.modelProfileId
            )

            is SkillsIntent.SetDefaultTaskPreset -> setDefaultTaskPreset(intent.presetId)
            SkillsIntent.StartCreate -> startCreate()
            SkillsIntent.DismissCreate -> _uiState.update {
                it.copy(creating = false, createTaskType = "", createName = "", createPrompt = "")
            }

            is SkillsIntent.SetCreateTaskType -> _uiState.update {
                it.copy(createTaskType = intent.taskType)
            }

            is SkillsIntent.SetCreateName -> _uiState.update { it.copy(createName = intent.name) }
            is SkillsIntent.SetCreatePrompt -> _uiState.update { it.copy(createPrompt = intent.prompt) }
            SkillsIntent.SaveCreate -> saveCreate()
        }
    }

    private fun submitSnapshot(snapshot: ProfileSnapshot) {
        taskPresets = snapshot.taskPresets
        promptPresets = snapshot.promptPresets
        val providerById = snapshot.providers.associateBy { it.id }
        val modelById = snapshot.models.associateBy { it.id }
        val modelLabelById = snapshot.models.associate { model ->
            val provider = providerById[model.providerId]
            model.id to buildString {
                append(provider?.name ?: "")
                if (provider != null) append(" / ")
                append(model.displayName)
            }
        }
        val groups = snapshot.taskPresets
            .groupBy { it.taskType }
            .map { (taskType, presets) ->
                AiSkillTaskGroupUi(
                    taskType = taskType,
                    taskLabel = aiTaskSceneLabel(taskType) ?: taskType,
                    presets = presets
                        .sortedWith(
                            compareByDescending<AiTaskPreset> { it.isDefault }
                                .thenBy { it.sortNumber }
                                .thenBy { it.createdAt }
                        )
                        .map { preset ->
                            AiSkillTaskPresetUi(
                                id = preset.id,
                                taskType = preset.taskType,
                                taskLabel = aiTaskSceneLabel(preset.taskType) ?: preset.taskType,
                                name = preset.name,
                                promptTemplate = preset.promptTemplate,
                                modelName = modelLabelById[preset.modelProfileId].orEmpty(),
                                modelBound = preset.modelProfileId.isNotBlank() &&
                                    modelById.containsKey(preset.modelProfileId),
                                isDefault = preset.isDefault
                            )
                        }
                        .toImmutableList()
                )
            }
            .sortedWith(
                compareBy<AiSkillTaskGroupUi> { group ->
                    knownTaskTypes.indexOf(group.taskType).let { if (it < 0) Int.MAX_VALUE else it }
                }.thenBy { it.taskLabel }
            )
        _uiState.update {
            it.copy(
                taskGroups = groups.toImmutableList(),
                promptPresets = snapshot.promptPresets
                    .map { preset ->
                        AiSkillPromptPresetUi(
                            id = preset.id,
                            name = preset.name,
                            instruction = preset.instruction,
                            builtIn = preset.builtIn
                        )
                    }
                    .toImmutableList(),
                models = snapshot.models
                    .map { model ->
                        AiSkillModelOptionUi(
                            modelProfileId = model.id,
                            label = modelLabelById[model.id].orEmpty()
                        )
                    }
                    .toImmutableList()
            )
        }
    }

    private fun exportTaskPreset(presetId: String) {
        val preset = taskPresets.firstOrNull { it.id == presetId } ?: return
        export(listOf(preset), emptyList())
    }

    private fun exportPromptPreset(presetId: String) {
        val preset = promptPresets.firstOrNull { it.id == presetId } ?: return
        export(emptyList(), listOf(preset))
    }

    private fun export(taskPresets: List<AiTaskPreset>, promptPresets: List<AiPromptPreset>) {
        viewModelScope.launch {
            runCatching { aiSkillPackUseCase.exportPack(taskPresets, promptPresets) }
                .onSuccess { json ->
                    _effects.tryEmit(SkillsEffect.CopyText(json))
                }
                .onFailure { error ->
                    _effects.tryEmit(SkillsEffect.ShowMessage(error.message ?: "导出失败"))
                }
        }
    }

    private fun parseImport(text: String) {
        viewModelScope.launch {
            aiSkillPackUseCase.parseImport(text)
                .onSuccess { preview ->
                    pendingImportPack = preview.pack
                    _uiState.update {
                        it.copy(
                            importPackName = preview.pack.name,
                            importItems = preview.items
                                .map { item ->
                                    AiSkillImportItemUi(
                                        index = item.index,
                                        typeLabel = if (
                                            item.type == AiSkillPackCodec.TYPE_TASK_PRESET
                                        ) {
                                            appCtx.getString(R.string.ai_skill_type_task_preset)
                                        } else {
                                            appCtx.getString(R.string.ai_skill_type_prompt_preset)
                                        },
                                        taskLabel = aiTaskSceneLabel(item.taskType) ?: item.taskType,
                                        name = item.name,
                                        prompt = item.prompt,
                                        paramsSummary = item.paramsSummary,
                                        modelMatched = item.modelMatch.status == AiSkillModelMatchStatus.MATCHED,
                                        modelLabel = if (item.modelMatch.status == AiSkillModelMatchStatus.MATCHED) {
                                            buildString {
                                                append(item.modelMatch.providerName)
                                                if (item.modelMatch.providerName.isNotBlank()) append(" / ")
                                                append(item.modelMatch.modelName)
                                            }
                                        } else {
                                            ""
                                        },
                                        sameContent = item.conflict.sameContent,
                                        // 内容相同的条目默认不勾选，导入只会产生冗余副本
                                        selected = !item.conflict.sameContent
                                    )
                                }
                                .toImmutableList()
                        )
                    }
                }
                .onFailure { error ->
                    _effects.tryEmit(
                        SkillsEffect.ShowMessage(
                            error.message ?: appCtx.getString(R.string.ai_skill_clipboard_invalid)
                        )
                    )
                }
        }
    }

    private fun clearImport() {
        pendingImportPack = null
        _uiState.update { it.copy(importItems = persistentListOf(), importPackName = "") }
    }

    private fun toggleImportItem(index: Int) {
        _uiState.update { state ->
            state.copy(
                importItems = state.importItems.map { item ->
                    if (item.index == index) item.copy(selected = !item.selected) else item
                }.toImmutableList()
            )
        }
    }

    private fun confirmImport() {
        val pack = pendingImportPack ?: return
        val selected = _uiState.value.importItems.filter { it.selected }.map { it.index }.toSet()
        if (selected.isEmpty()) {
            _effects.tryEmit(SkillsEffect.ShowMessage(appCtx.getString(R.string.ai_skill_none_selected)))
            return
        }
        viewModelScope.launch {
            aiSkillPackUseCase.commitImport(pack, selected)
                .onSuccess { result ->
                    appCtx.toastOnUi(
                        appCtx.getString(
                            R.string.ai_skill_import_done,
                            result.imported,
                            result.skipped
                        )
                    )
                    promptPresetRefresh.value++
                    clearImport()
                }
                .onFailure { error ->
                    _effects.tryEmit(SkillsEffect.ShowMessage(error.message ?: "导入失败"))
                }
        }
    }

    private fun deleteTaskPreset(presetId: String) {
        viewModelScope.launch {
            runCatching { aiProfileGateway.deleteTaskPreset(presetId) }
                .onSuccess {
                    appCtx.toastOnUi(appCtx.getString(R.string.ai_skill_deleted))
                }
                .onFailure { error ->
                    _effects.tryEmit(SkillsEffect.ShowMessage(error.message ?: "删除失败"))
                }
        }
    }

    private fun deletePromptPreset(presetId: String) {
        viewModelScope.launch {
            runCatching { aiPromptPresetGateway.deletePreset(presetId) }
                .onSuccess {
                    appCtx.toastOnUi(appCtx.getString(R.string.ai_skill_deleted))
                    promptPresetRefresh.value++
                }
                .onFailure { error ->
                    _effects.tryEmit(SkillsEffect.ShowMessage(error.message ?: "删除失败"))
                }
        }
    }

    private fun bindTaskPresetModel(presetId: String, modelProfileId: String) {
        viewModelScope.launch {
            runCatching { aiProfileGateway.bindTaskPresetModel(presetId, modelProfileId) }
                .onSuccess {
                    appCtx.toastOnUi(appCtx.getString(R.string.ai_skill_model_bound))
                }
                .onFailure { error ->
                    _effects.tryEmit(SkillsEffect.ShowMessage(error.message ?: "绑定失败"))
                }
        }
    }

    private fun setDefaultTaskPreset(presetId: String) {
        viewModelScope.launch {
            runCatching { aiProfileGateway.setDefaultTaskPreset(presetId) }
                .onSuccess {
                    appCtx.toastOnUi(appCtx.getString(R.string.ai_skill_default_set))
                }
                .onFailure { error ->
                    _effects.tryEmit(SkillsEffect.ShowMessage(error.message ?: "设置失败"))
                }
        }
    }

    private fun startCreate() {
        _uiState.update {
            it.copy(creating = true, createTaskType = "", createName = "", createPrompt = "")
        }
    }

    private fun saveCreate() {
        val state = _uiState.value
        val taskType = state.createTaskType
        val name = state.createName.trim()
        val prompt = state.createPrompt.trim()
        if (taskType.isBlank()) {
            _effects.tryEmit(SkillsEffect.ShowMessage(appCtx.getString(R.string.ai_skill_task_type_required)))
            return
        }
        if (name.isBlank()) {
            _effects.tryEmit(SkillsEffect.ShowMessage(appCtx.getString(R.string.ai_skill_name_required)))
            return
        }
        if (prompt.isBlank()) {
            _effects.tryEmit(SkillsEffect.ShowMessage(appCtx.getString(R.string.ai_skill_prompt_empty)))
            return
        }
        viewModelScope.launch {
            runCatching {
                // 新建预设默认跟随当前默认模型，避免落成待绑定态
                val modelProfileId = taskPresets.firstOrNull { it.isDefault }?.modelProfileId
                    .orEmpty()
                val now = System.currentTimeMillis()
                aiProfileGateway.saveImportedTaskPreset(
                    AiTaskPreset(
                        id = "preset_${UUID.randomUUID().toString().replace("-", "")}",
                        taskType = taskType,
                        name = name,
                        modelProfileId = modelProfileId,
                        promptTemplate = prompt,
                        paramsJson = GSON.toJson(AiGenerationParams()),
                        enabled = true,
                        isDefault = false,
                        sortNumber = 0,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }.onSuccess {
                appCtx.toastOnUi(appCtx.getString(R.string.ai_skill_created))
                _uiState.update {
                    it.copy(creating = false, createTaskType = "", createName = "", createPrompt = "")
                }
            }.onFailure { error ->
                _effects.tryEmit(SkillsEffect.ShowMessage(error.message ?: "保存失败"))
            }
        }
    }

    private companion object {
        val knownTaskTypes = listOf(
            AiTaskType.CHAT,
            AiTaskType.TRANSLATE_CHAPTER,
            AiTaskType.SUMMARIZE_CHAPTER,
            AiTaskType.SUMMARIZE_BOOK,
            AiTaskType.EXPLAIN_SELECTION,
            AiTaskType.CLEAN_SELECTION,
            AiTaskType.TEXT_FACTORY,
            AiTaskType.REWRITE_TEXT,
            AiTaskType.ANALYZE_SPEECH,
            AiTaskType.IDENTIFY_CHARACTERS,
            AiTaskType.BOOKSHELF_AUTO_GROUP,
            AiTaskType.AUTHOR_BIO,
            AiTaskType.TOC_RULE,
        )
    }
}
