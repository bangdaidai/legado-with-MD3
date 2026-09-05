package io.legado.app.ui.config.ai.skills

import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class SkillsUiState(
    val taskGroups: ImmutableList<AiSkillTaskGroupUi> = persistentListOf(),
    val promptPresets: ImmutableList<AiSkillPromptPresetUi> = persistentListOf(),
    val models: ImmutableList<AiSkillModelOptionUi> = persistentListOf(),
    val importItems: ImmutableList<AiSkillImportItemUi> = persistentListOf(),
    val importPackName: String = "",
    val creating: Boolean = false,
    val createTaskType: String = "",
    val createName: String = "",
    val createPrompt: String = ""
)

@Stable
data class AiSkillTaskGroupUi(
    val taskType: String,
    val taskLabel: String,
    val presets: ImmutableList<AiSkillTaskPresetUi> = persistentListOf()
)

@Stable
data class AiSkillTaskPresetUi(
    val id: String,
    val taskType: String,
    val taskLabel: String,
    val name: String,
    val promptTemplate: String,
    val modelName: String,
    /** 模型绑定缺失时运行时会跳过该预设，UI 需要显式标注「待绑定」 */
    val modelBound: Boolean,
    val isDefault: Boolean
)

@Stable
data class AiSkillPromptPresetUi(
    val id: String,
    val name: String,
    val instruction: String,
    val builtIn: Boolean
)

@Stable
data class AiSkillModelOptionUi(
    val modelProfileId: String,
    val label: String
)

@Stable
data class AiSkillImportItemUi(
    val index: Int,
    val typeLabel: String,
    val taskLabel: String,
    val name: String,
    val prompt: String,
    val paramsSummary: String,
    val modelMatched: Boolean,
    val modelLabel: String,
    val sameContent: Boolean,
    val selected: Boolean
)

sealed interface SkillsIntent {
    data class ExportTaskPreset(val presetId: String) : SkillsIntent
    data class ExportPromptPreset(val presetId: String) : SkillsIntent
    data class ParseImport(val text: String) : SkillsIntent
    data object DismissImport : SkillsIntent
    data class ToggleImportItem(val index: Int) : SkillsIntent
    data object ConfirmImport : SkillsIntent
    data class DeleteTaskPreset(val presetId: String) : SkillsIntent
    data class DeletePromptPreset(val presetId: String) : SkillsIntent
    data class BindTaskPresetModel(val presetId: String, val modelProfileId: String) : SkillsIntent
    data class SetDefaultTaskPreset(val presetId: String) : SkillsIntent
    data object StartCreate : SkillsIntent
    data object DismissCreate : SkillsIntent
    data class SetCreateTaskType(val taskType: String) : SkillsIntent
    data class SetCreateName(val name: String) : SkillsIntent
    data class SetCreatePrompt(val prompt: String) : SkillsIntent
    data object SaveCreate : SkillsIntent
}

sealed interface SkillsEffect {
    data class ShowMessage(val message: String) : SkillsEffect
    data class CopyText(val text: String) : SkillsEffect
}
