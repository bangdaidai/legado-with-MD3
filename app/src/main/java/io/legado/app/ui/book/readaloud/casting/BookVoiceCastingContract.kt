package io.legado.app.ui.book.readaloud.casting

import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class BookVoiceCastingUiState(
    val bookUrl: String,
    val isLoading: Boolean = true,
    val items: ImmutableList<VoiceCastingItemUi> = persistentListOf(),
    val voices: ImmutableList<VoiceOptionUi> = persistentListOf(),
    val picker: VoicePickerUi? = null,
    /** 正在合成试听音频的音色，用来在那一行上显示转圈 */
    val previewingVoiceId: String? = null,
)

@Stable
data class VoiceCastingItemUi(
    val subjectType: String,
    val subjectId: String,
    val kind: CastingSubjectKind,
    val name: String,
    val description: String = "",
    val avatarUri: String? = null,
    val hasBinding: Boolean = false,
    val voiceName: String = "",
    val voiceAvailable: Boolean = false,
)

@Stable
data class VoiceOptionUi(
    val id: String,
    val name: String,
    val engineType: String,
    val engineName: String,
    val selectable: Boolean,
    /** 见 [io.legado.app.help.readaloud.ReadAloudVoiceTraits] 的 GENDER_* 常量 */
    val gender: String = "",
    /** 音色脚本声明的风格标签, 例如「少年」「清亮」 */
    val descriptors: ImmutableList<String> = persistentListOf(),
)

@Stable
data class VoicePickerUi(
    val subjectType: String,
    val subjectId: String,
    val kind: CastingSubjectKind,
    val name: String,
    val selectedVoiceId: String?,
)

enum class CastingSubjectKind {
    Narrator,
    UnknownMale,
    UnknownFemale,
    Unknown,
    Character,

    /** AI 认出但还没转正的说话人，对应草稿角色卡 */
    TemporaryCharacter,
}

sealed interface BookVoiceCastingIntent {
    data object Refresh : BookVoiceCastingIntent
    data class OpenVoicePicker(val subjectType: String, val subjectId: String) :
        BookVoiceCastingIntent
    data object DismissVoicePicker : BookVoiceCastingIntent
    data class AssignVoice(val voiceId: String) : BookVoiceCastingIntent
    data class PreviewVoice(val voiceId: String) : BookVoiceCastingIntent
    data object ClearBinding : BookVoiceCastingIntent
    data class PromoteCharacter(val subjectId: String) : BookVoiceCastingIntent
}

sealed interface BookVoiceCastingEffect {
    data class ShowToast(val message: String) : BookVoiceCastingEffect
    data class PlayPreview(val path: String) : BookVoiceCastingEffect
}
