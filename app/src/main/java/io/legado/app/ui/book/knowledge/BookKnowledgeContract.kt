package io.legado.app.ui.book.knowledge

import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class CharacterDetailUiState(
    val bookUrl: String = "",
    val characterId: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val name: String = "",
    val aliasesText: String = "",
    val avatarUri: String = "",
    val tags: ImmutableList<String> = persistentListOf(),
    val tagInput: String = "",
    val role: String = "",
    val voiceGender: String = "unknown",
    val isProtagonist: Boolean = false,
    /** 声音池显示名：落库时按名字查池，写进档案的 voicePoolId（枚举年龄段一起写 voiceAgeBand）；空 = 未分配。 */
    val voicePool: String = "",
    val poolNames: ImmutableList<String> = persistentListOf(),
    val personality: String = "",
    val summary: String = "",
    val events: ImmutableList<CharacterEventUi> = persistentListOf(),
    val relations: ImmutableList<CharacterRelationUi> = persistentListOf(),
)

@Stable
data class CharacterEventUi(
    val id: String,
    val title: String,
    val content: String,
)

@Stable
data class CharacterRelationUi(
    val id: String,
    val fromName: String,
    val toName: String,
    val relationType: String,
    val summary: String,
    val attitude: String,
)

sealed interface CharacterDetailIntent {
    /**
     * 弹窗里的整包应用：不管这一次改了几栏，一次「应用」= 一个动作 = 一行写入。
     *
     * 不要再按栏拆成多个 Set* 动作：每个动作都会把整行档案原样写回库一遍，而前面几遍带的还是用户
     * 改动前的角色、声音性别与声音池，各遍谁最后落库不作保证——落库顺序一翻，界面就成了
     * 「选了男配、返回再进来又空了」。以后栏位加到十栏也是往这一个动作里加字段，不是多发几个动作。
     */
    data class ApplyProfile(
        val name: String,
        val aliasesText: String,
        val role: String,
        val voiceGender: String,
        val voicePool: String,
        val isProtagonist: Boolean,
    ) : CharacterDetailIntent

    data class SetAvatarUri(val value: String) : CharacterDetailIntent
    data class SetTagInput(val value: String) : CharacterDetailIntent
    data class AddTag(val tag: String) : CharacterDetailIntent
    data class RemoveTag(val index: Int) : CharacterDetailIntent
    data class SetPersonality(val value: String) : CharacterDetailIntent
    data class SetSummary(val value: String) : CharacterDetailIntent
    data class Delete(
        val deleteRelations: Boolean,
        val deleteEvents: Boolean,
    ) : CharacterDetailIntent
}

sealed interface CharacterDetailEffect {
    data class ShowToast(val message: String) : CharacterDetailEffect
    data object NavigateBack : CharacterDetailEffect
}

@Stable
data class CharacterNetworkUiState(
    val bookUrl: String = "",
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val characters: ImmutableList<CharacterNodeUi> = persistentListOf(),
    val relations: ImmutableList<CharacterRelationEditorUi> = persistentListOf(),
    val newFromName: String = "",
    val newToName: String = "",
    val newRelationType: String = "",
    val newSummary: String = "",
    val newAttitude: String = "",
)

@Stable
data class CharacterNodeUi(
    val id: String,
    val name: String,
    val avatarUri: String?,
    val summary: String,
)

@Stable
data class CharacterRelationEditorUi(
    val id: String,
    val fromCharacterId: String,
    val toCharacterId: String,
    val fromName: String,
    val toName: String,
    val relationType: String,
    val summary: String,
    val attitude: String,
)

sealed interface CharacterNetworkIntent {
    data class OpenCharacter(val characterId: String) : CharacterNetworkIntent
    data class SetNewFromName(val value: String) : CharacterNetworkIntent
    data class SetNewToName(val value: String) : CharacterNetworkIntent
    data class SetNewRelationType(val value: String) : CharacterNetworkIntent
    data class SetNewSummary(val value: String) : CharacterNetworkIntent
    data class SetNewAttitude(val value: String) : CharacterNetworkIntent
    data class SetRelationType(val relationId: String, val value: String) : CharacterNetworkIntent
    data class SetRelationSummary(val relationId: String, val value: String) :
        CharacterNetworkIntent

    data class SetRelationAttitude(val relationId: String, val value: String) :
        CharacterNetworkIntent

    data class SaveRelation(val relationId: String) : CharacterNetworkIntent
    data class DeleteRelation(val relationId: String) : CharacterNetworkIntent
    data object AddRelation : CharacterNetworkIntent
}

sealed interface CharacterNetworkEffect {
    data class OpenCharacterDetail(val bookUrl: String, val characterId: String) :
        CharacterNetworkEffect

    data class ShowToast(val message: String) : CharacterNetworkEffect
}
