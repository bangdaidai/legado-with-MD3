package io.legado.app.ui.book.bookplate

import androidx.compose.runtime.Stable
import io.legado.app.data.entities.BookplateTemplate
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class BookplateManageUiState(
    val loading: Boolean = false,
    val groups: ImmutableList<String> = persistentListOf(),
    val selectedGroup: String? = null,
    val templates: ImmutableList<BookplateTemplate> = persistentListOf(),
    val selectedTemplateId: Long = 0L,
    val editing: BookplateTemplate? = null,
    val showGroupManage: Boolean = false,
    val deleteConfirm: BookplateTemplate? = null,
)

sealed interface BookplateManageIntent {
    data object Load : BookplateManageIntent
    data class SelectGroup(val group: String?) : BookplateManageIntent
    data class SelectTemplate(val id: Long) : BookplateManageIntent
    data class StartEdit(val template: BookplateTemplate?) : BookplateManageIntent
    data object CancelEdit : BookplateManageIntent
    data class SaveTemplate(val name: String, val html: String, val group: String) : BookplateManageIntent
    data class RequestDelete(val template: BookplateTemplate) : BookplateManageIntent
    data object ConfirmDelete : BookplateManageIntent
    data object DismissDelete : BookplateManageIntent
    data object ShowGroupManage : BookplateManageIntent
    data object DismissGroupManage : BookplateManageIntent
    data class RenameGroup(val oldName: String, val newName: String) : BookplateManageIntent
    data class DeleteGroup(val group: String) : BookplateManageIntent
    data object RestoreBuiltins : BookplateManageIntent
}

sealed interface BookplateManageEffect {
    data class ShowToast(val message: String) : BookplateManageEffect
}
