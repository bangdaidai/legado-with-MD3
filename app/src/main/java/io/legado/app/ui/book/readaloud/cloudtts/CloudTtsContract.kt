package io.legado.app.ui.book.readaloud.cloudtts

import android.net.Uri
import androidx.compose.runtime.Stable
import io.legado.app.data.entities.HttpTTS
import io.legado.app.help.readaloud.ReadAloudVoiceTraits
import io.legado.app.ui.widget.components.importComponents.BaseImportUiState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

@Stable
data class CloudTtsUiState(
    val loading: Boolean = true,
    val engines: ImmutableList<CloudTtsEngineItemUi> = persistentListOf(),
    val systemEngines: ImmutableList<TtsManagedEngineItemUi> = persistentListOf(),
    val httpEngines: ImmutableList<TtsManagedEngineItemUi> = persistentListOf(),
    val voices: ImmutableList<CloudTtsVoiceItemUi> = persistentListOf(),
    val discoveredVoices: ImmutableList<CloudTtsDiscoveredVoiceUi> = persistentListOf(),
    val availableEngines: ImmutableList<TtsEngineOptionUi> = persistentListOf(),
    val engineEditor: CloudTtsEngineEditorUi? = null,
    val voiceEditor: TtsVoicePresetEditorUi? = null,
    val httpTtsEditor: HttpTTS? = null,
    val httpTtsImportState: BaseImportUiState<HttpTTS> = BaseImportUiState.Idle,
    val showVoiceEnginePicker: Boolean = false,
    val activeDialog: CloudTtsDialog? = null,
    val testing: Boolean = false,
    val discovering: Boolean = false,
    /** 音色列表里正在试听的那条，用来给按钮加高亮 */
    val previewingVoiceId: String? = null,
    /** 添加音色预设弹窗里正在试听的那条候选音色 */
    val previewingCandidateVoiceId: String? = null,
    /** 音色列表的性别筛选，空串为不筛选；取值见 [io.legado.app.help.readaloud.ReadAloudVoiceTraits] */
    val voiceGenderFilter: String = "",
    val selectedTab: CloudTtsTab = CloudTtsTab.Voices,
    /** 试听文本（用户改过的或内置默认），试听悬浮窗打开时填它。 */
    val previewText: String = "",
    /**
     * 音色批量选择模式：开了之后行尾的试听/删除按钮换成勾选，
     * 列表上方出现「全选 / 删除(n) / 退出」。
     */
    val voiceBatchMode: Boolean = false,
    val selectedVoiceIds: ImmutableSet<String> = persistentSetOf(),
    /** 「添加到声音池」弹层：非空 = 打开，带着要加进去的音色 id。 */
    val poolPicker: VoicePoolPickerUi? = null,
    /**
     * 被用户排除的音色数（源自带目录里不想用的那些）。
     *
     * 大于 0 时才给「恢复」入口——没有排除项时那个按钮按下去没有任何意义。
     */
    val excludedVoiceCount: Int = 0,
)

/** 把音色加进角色声音池的弹层内容：待加的音色 + 可选的池列表。 */
@Stable
data class VoicePoolPickerUi(
    val voiceIds: ImmutableList<String>,
    val voiceLabel: String,
    val pools: ImmutableList<VoicePoolOptionUi>,
)

@Stable
data class VoicePoolOptionUi(
    val id: String,
    val name: String,
    val groupName: String,
    val memberCount: Int,
)

enum class CloudTtsTab { Voices, Engines }

@Stable
data class CloudTtsEngineItemUi(
    val id: String,
    val title: String,
    val summary: String,
    val selected: Boolean = false,
)

@Stable
data class TtsManagedEngineItemUi(
    val engineType: String,
    val engineId: String,
    val title: String,
    val summary: String = "",
    val selected: Boolean = false,
    val loginUrl: String = "",
    val hasScript: Boolean = false,
)
@Stable data class CloudTtsVoiceItemUi(
    val id: String,
    val title: String,
    val summary: String,
    val deletable: Boolean,
    val editable: Boolean,
    /** 筛选用的性别，取值见 [io.legado.app.help.readaloud.ReadAloudVoiceTraits] */
    val gender: String = "",
    /** 第一行的小标记：性别 / 语言 / 风格（图标化短块，不再重复进第二行） */
    val genderLabel: String = "",
    val languageLabel: String = "",
    val styleLabel: String = "",
    /** 第二行横滑标签：引擎名 / 所在声音池 / 用户标注等；已滤掉与第一行重复的项 */
    val secondaryTags: List<String> = emptyList(),
    val engineName: String = "",
)

/** 编辑音色预设对话框里的声音池勾选行 */
@Stable data class VoicePoolCheckUi(
    val poolId: String,
    val name: String,
    val checked: Boolean,
)
@Stable data class TtsEngineOptionUi(
    val engineType: String,
    val engineId: String,
    val title: String,
    val summary: String,
    val catalogHint: String,
    val remoteCatalog: Boolean = false,
)
@Stable data class CloudTtsDiscoveredVoiceUi(
    val id: String,
    val label: String,
    val locale: String,
    val styles: ImmutableList<String>,
    val roles: ImmutableList<String>,
)

@Stable
data class CloudTtsEngineEditorUi(
    val editingEngineId: String? = null,
    val name: String = "",
    val provider: String = "mimo",
    val baseUrl: String = "",
    val apiKey: String = "",
    val secretKey: String = "",
    val region: String = "",
    val appId: String = "",
    val model: String = "",
    val optionsJson: String = "{}",
)

@Stable
data class TtsVoicePresetEditorUi(
    val editingVoiceId: String? = null,
    val engineType: String = "",
    val engineId: String = "",
    val engineName: String = "",
    val voiceId: String = "",
    val voiceName: String = "",
    val gender: String = ReadAloudVoiceTraits.GENDER_UNKNOWN,
    val notes: String = "",
    val locale: String = "",
    val style: String = "",
    val role: String = "",
    val instructions: String = "",
    val automaticEmotion: Boolean = true,
    val characterPersonality: Boolean = true,
    val thoughtPerformance: Boolean = true,
    val speed: String = "1.0",
    val pitch: String = "1.0",
    val volume: String = "1.0",
    val format: String = "mp3",
    val formatOptions: ImmutableList<String> = persistentListOf(),
    /**
     * 用户给这条音色的分类标注（`voice_labels`）。空串 = 未标注。
     *
     * 这三栏与引擎自己声明的那份分开存：引擎声明在音色行的 `traitsJson` 里，同步时会被
     * 逐字段重建；标注在旁表里，谁也不覆盖谁，读取侧按「标注 > 池分类 > 声明 > 名字推测」回落。
     */
    val labelGender: String = "",
    val labelAgeBand: String = "",
    /** 逗号分隔的自由文本；入库前按 [io.legado.app.help.readaloud.cast.VoiceLabelStore.parseTags] 拆。 */
    val labelTags: String = "",
    /** 未标注那几栏当前实际按什么走（已本地化，只作提示，绝不写回表）。 */
    val labelFallback: String = "",
    /**
     * true = 目录同步的音色（managedBy 不是 user）：预设参数不可改，
     * 只开放「分类标注」与「声音池」两区，保存走轻量路径（不重建音色行）。
     */
    val readonlyPreset: Boolean = false,
    /** 该音色在哪些声音池里（编辑对话框内直接勾选，保存时同步成员关系） */
    val memberPools: ImmutableList<VoicePoolCheckUi> = persistentListOf(),
)

sealed interface CloudTtsIntent {
    data class SelectTab(val tab: CloudTtsTab) : CloudTtsIntent
    data class SetBookContext(val bookUrl: String?) : CloudTtsIntent
    data object AddEngine : CloudTtsIntent
    data object AddVoice : CloudTtsIntent
    data class AddVoiceForEngine(val engineType: String, val engineId: String) : CloudTtsIntent
    data class EditEngine(val id: String) : CloudTtsIntent
    data class DeleteEngine(val id: String) : CloudTtsIntent
    data class SetDefaultEngine(val engineType: String, val engineId: String) : CloudTtsIntent
    data object ApplyDefaultEngineGlobally : CloudTtsIntent
    data object ApplyDefaultEngineForBook : CloudTtsIntent
    data class EditHttpTts(val engineId: String? = null) : CloudTtsIntent
    data class NewScriptTtsFromTemplate(val asset: String) : CloudTtsIntent
    data class SaveHttpTts(val value: HttpTTS) : CloudTtsIntent
    data class DeleteHttpTts(val engineId: String) : CloudTtsIntent
    data class OpenHttpTtsLogin(val engineId: String) : CloudTtsIntent
    data object DismissHttpTtsEditor : CloudTtsIntent
    data class ImportHttpTtsSource(val text: String) : CloudTtsIntent
    data object ImportHttpTtsFile : CloudTtsIntent
    data class ImportHttpTtsFileSelected(val uri: Uri) : CloudTtsIntent
    data object CancelHttpTtsImport : CloudTtsIntent
    data class ToggleHttpTtsImportSelection(val index: Int) : CloudTtsIntent
    data class ToggleHttpTtsImportAll(val selected: Boolean) : CloudTtsIntent
    data class UpdateHttpTtsImportItem(val index: Int, val value: HttpTTS) : CloudTtsIntent
    data object SaveImportedHttpTts : CloudTtsIntent
    data object ExportHttpTtsFile : CloudTtsIntent
    data class ExportHttpTtsFileSelected(val uri: Uri) : CloudTtsIntent
    data object ExportHttpTtsUrl : CloudTtsIntent
    data object ClearTtsCache : CloudTtsIntent
       data class RequestDeleteVoice(val id: String) : CloudTtsIntent
    data object ConfirmDeleteVoice : CloudTtsIntent
    /** 进入/退出音色批量选择模式（退出会清空已勾选的）。 */
    data object ToggleVoiceBatchMode : CloudTtsIntent
    data class ToggleVoiceSelection(val id: String) : CloudTtsIntent
    /** 全选/取消全选当前列表里可删的音色。 */
    data object ToggleSelectAllVoices : CloudTtsIntent
    data object RequestDeleteSelectedVoices : CloudTtsIntent
    data object ConfirmDeleteSelectedVoices : CloudTtsIntent
    /** 把排除掉的音色全部放回来（源自带目录的那些）。 */
    data object RestoreExcludedVoices : CloudTtsIntent
    /** 打开「添加到声音池」弹层；[ids] 为空时按批量勾选的那一批处理。 */
    data class RequestAddToPool(val ids: List<String>) : CloudTtsIntent
    data object DismissPoolPicker : CloudTtsIntent
    data class ConfirmAddToPool(val poolId: String) : CloudTtsIntent
    data class EditVoice(val id: String) : CloudTtsIntent
    /** 试听音色表里已保存的一条音色 */
    data class PreviewSavedVoice(val id: String) : CloudTtsIntent
    data class RequestRenameVoice(val id: String) : CloudTtsIntent
    /**
     * 长按音色行：把该音色设为/取消全局默认旁白音色。
     *
     * 只在多角色朗读计划里本书没绑旁白时兜底生效，与「默认引擎」（未开多角色时使用）互不相干；
     * 再长按一次当前默认即取消，回到跟随引擎默认。
     */
    data class ToggleDefaultNarratorVoice(val id: String) : CloudTtsIntent
    /** 切换音色列表的性别筛选，传空串为取消筛选 */
    data class SetVoiceGenderFilter(val gender: String) : CloudTtsIntent
    data class ConfirmRenameVoice(val name: String) : CloudTtsIntent
    data class UpdateEngineEditor(val editor: CloudTtsEngineEditorUi) : CloudTtsIntent
    data class UpdateVoiceEditor(val editor: TtsVoicePresetEditorUi) : CloudTtsIntent
    data object DismissEngineEditor : CloudTtsIntent
    data object DismissVoiceEditor : CloudTtsIntent
    data object DismissVoiceEnginePicker : CloudTtsIntent
    data class SelectEngine(val engineType: String, val engineId: String) : CloudTtsIntent
    data object DiscoverVoices : CloudTtsIntent
    data class SelectVoice(val id: String) : CloudTtsIntent
    data object TestEngine : CloudTtsIntent
    data object Preview : CloudTtsIntent

    /** 在添加音色预设弹窗里直接试听某条候选音色，不必先选中它 */
    data class PreviewCandidateVoice(val voiceId: String) : CloudTtsIntent
    /**
     * 列表行上的试听按钮：不打开编辑弹窗，直接按该音色合成一句。
     * [text] 非空 = 用这句（试听悬浮窗里正在编辑的草稿），否则用已保存的默认文本。
     */
    data class PreviewVoice(val id: String, val text: String? = null) : CloudTtsIntent
    /** 保存默认试听文本（试听悬浮窗的「确定」）。 */
    data class SavePreviewText(val text: String) : CloudTtsIntent
    data object Save : CloudTtsIntent
    data object DismissError : CloudTtsIntent
    data object CopyError : CloudTtsIntent
    data class ReportError(val message: String) : CloudTtsIntent
}

sealed interface CloudTtsEffect {
    data class ShowToast(val message: String) : CloudTtsEffect
    data class PlayPreview(val path: String) : CloudTtsEffect
    data class CopyText(val text: String, val message: String) : CloudTtsEffect
    data object OpenHttpTtsImportPicker : CloudTtsEffect
    data object OpenHttpTtsExportPicker : CloudTtsEffect
    data class OpenHttpTtsLogin(val engineId: Long) : CloudTtsEffect
}

sealed interface CloudTtsDialog {
    data class Error(val message: String) : CloudTtsDialog
    data class DeleteVoice(val id: String, val title: String) : CloudTtsDialog
    data class RenameVoice(val id: String, val currentName: String) : CloudTtsDialog
    /** 批量删除确认：[count] 只用于文案，真正删的是 [ids]。 */
    data class DeleteVoices(val ids: ImmutableList<String>, val count: Int) : CloudTtsDialog
    data class DefaultEngineScope(val value: String?, val title: String) : CloudTtsDialog
}
