package io.legado.app.ui.book.shareCard

import androidx.compose.runtime.Stable
import io.legado.app.data.entities.ShareCardTemplate
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class ShareCardManageUiState(
    val loading: Boolean = false,
    val groups: ImmutableList<String> = persistentListOf(),
    val selectedGroup: String? = null,
    val templates: ImmutableList<ShareCardTemplate> = persistentListOf(),
    val defaultTemplateId: Long = 0L,
    val editing: ShareCardTemplate? = null,
    val previewTemplate: ShareCardTemplate? = null,
    val showGroupManage: Boolean = false,
    val showHelp: Boolean = false,
    val deleteConfirm: ShareCardTemplate? = null,
)

sealed interface ShareCardManageIntent {
    data object Load : ShareCardManageIntent
    data class SelectGroup(val group: String?) : ShareCardManageIntent
    data class SetDefault(val id: Long) : ShareCardManageIntent
    data class StartEdit(val template: ShareCardTemplate?) : ShareCardManageIntent
    data object CancelEdit : ShareCardManageIntent
    data class SaveTemplate(val name: String, val html: String, val group: String) : ShareCardManageIntent
    data class ShowPreview(val template: ShareCardTemplate) : ShareCardManageIntent
    data object DismissPreview : ShareCardManageIntent
    data class RequestDelete(val template: ShareCardTemplate) : ShareCardManageIntent
    data object ConfirmDelete : ShareCardManageIntent
    data object DismissDelete : ShareCardManageIntent
    data object ShowGroupManage : ShareCardManageIntent
    data object DismissGroupManage : ShareCardManageIntent
    data class RenameGroup(val oldName: String, val newName: String) : ShareCardManageIntent
    data class DeleteGroup(val group: String) : ShareCardManageIntent
    data object ShowHelp : ShareCardManageIntent
    data object DismissHelp : ShareCardManageIntent
    data object RestoreBuiltins : ShareCardManageIntent
}

sealed interface ShareCardManageEffect {
    data class ShowToast(val message: String) : ShareCardManageEffect
}
