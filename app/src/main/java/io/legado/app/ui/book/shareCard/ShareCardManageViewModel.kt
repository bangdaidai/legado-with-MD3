package io.legado.app.ui.book.shareCard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.ShareCardTemplate
import io.legado.app.data.repository.ShareCardRepository
import io.legado.app.help.book.ShareCardGenerator
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareCardManageViewModel(
    private val repository: ShareCardRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ShareCardManageUiState(
            defaultTemplateId = repository.getSelectedTemplateId()
        )
    )
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<ShareCardManageEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    init {
        onIntent(ShareCardManageIntent.Load)
    }

    fun onIntent(intent: ShareCardManageIntent) {
        when (intent) {
            is ShareCardManageIntent.Load -> load()
            is ShareCardManageIntent.SelectGroup -> selectGroup(intent.group)
            is ShareCardManageIntent.SetDefault -> setDefault(intent.id)
            is ShareCardManageIntent.StartEdit -> _uiState.update {
                // 新建模板默认归入空分组（未分组），用户可在编辑时自行选择/输入分组
                it.copy(editing = intent.template ?: ShareCardTemplate(groupName = ""))
            }
            is ShareCardManageIntent.CancelEdit -> _uiState.update { it.copy(editing = null) }
            is ShareCardManageIntent.SaveTemplate -> saveTemplate(intent.name, intent.html, intent.group)
            is ShareCardManageIntent.ShowPreview -> _uiState.update { it.copy(previewTemplate = intent.template) }
            is ShareCardManageIntent.DismissPreview -> _uiState.update { it.copy(previewTemplate = null) }
            is ShareCardManageIntent.RequestDelete -> _uiState.update { it.copy(deleteConfirm = intent.template) }
            is ShareCardManageIntent.ConfirmDelete -> confirmDelete()
            is ShareCardManageIntent.DismissDelete -> _uiState.update { it.copy(deleteConfirm = null) }
            is ShareCardManageIntent.ShowGroupManage -> _uiState.update { it.copy(showGroupManage = true) }
            is ShareCardManageIntent.DismissGroupManage -> _uiState.update { it.copy(showGroupManage = false) }
            is ShareCardManageIntent.RenameGroup -> renameGroup(intent.oldName, intent.newName)
            is ShareCardManageIntent.DeleteGroup -> deleteGroup(intent.group)
            is ShareCardManageIntent.ToggleGroupScene -> toggleGroupScene(intent.group, intent.sceneKey)
            is ShareCardManageIntent.ShowHelp -> _uiState.update { it.copy(showHelp = true) }
            is ShareCardManageIntent.DismissHelp -> _uiState.update { it.copy(showHelp = false) }
            is ShareCardManageIntent.RestoreBuiltins -> restoreBuiltins()
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            withContext(Dispatchers.IO) {
                ShareCardGenerator.getOrCreateBuiltinTemplates()
            }
            val selectedGroup = _uiState.value.selectedGroup
            val groups: List<String>
            val templates: List<ShareCardTemplate>
            val sceneMap: Map<String, List<String>>
            withContext(Dispatchers.IO) {
                groups = repository.getDistinctGroupNames()
                templates = if (selectedGroup == null) {
                    repository.getAll()
                } else {
                    repository.getByGroupName(selectedGroup)
                }
                sceneMap = repository.getSceneGroupMap()
            }
            _uiState.update {
                it.copy(
                    loading = false,
                    groups = groups.toImmutableList(),
                    templates = templates.toImmutableList(),
                    sceneGroupMap = sceneMap,
                )
            }
        }
    }

    private fun selectGroup(group: String?) {
        if (group == _uiState.value.selectedGroup) return
        _uiState.update { it.copy(selectedGroup = group, templates = persistentListOf()) }
        viewModelScope.launch {
            val templates = withContext(Dispatchers.IO) {
                if (group == null) repository.getAll()
                else repository.getByGroupName(group)
            }
            _uiState.update { it.copy(templates = templates.toImmutableList()) }
        }
    }

    private fun setDefault(id: Long) {
        _uiState.update { it.copy(defaultTemplateId = id) }
        repository.setSelectedTemplateId(id)
    }

    private fun saveTemplate(name: String, html: String, group: String) {
        val editing = _uiState.value.editing ?: return
        if (name.isBlank()) {
            _effects.tryEmit(ShareCardManageEffect.ShowToast("名称不能为空"))
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                val toSave = editing.copy(
                    name = name,
                    htmlContent = html,
                    updateTime = now,
                    createTime = if (editing.createTime == 0L) now else editing.createTime,
                    groupName = group.ifBlank { _uiState.value.selectedGroup ?: "" },
                )
                if (toSave.id == 0L) {
                    repository.insert(toSave)
                } else {
                    repository.update(toSave)
                }
            }
            _uiState.update { it.copy(editing = null) }
            reloadAll()
        }
    }

    private fun confirmDelete() {
        val template = _uiState.value.deleteConfirm ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.delete(template)
            }
            _uiState.update { it.copy(deleteConfirm = null) }
            reloadAll()
        }
    }

    private fun renameGroup(oldName: String, newName: String) {
        if (newName.isBlank() || oldName == newName) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.updateGroupName(oldName, newName)
                repository.renameSceneGroupKey(oldName, newName)
            }
            // update selectedGroup if it was renamed
            _uiState.update {
                it.copy(selectedGroup = if (it.selectedGroup == oldName) newName else it.selectedGroup)
            }
            reloadAll()
        }
    }

    private fun deleteGroup(group: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.deleteByGroupName(group)
                repository.removeSceneGroupKey(group)
            }
            _uiState.update {
                it.copy(selectedGroup = if (it.selectedGroup == group) null else it.selectedGroup)
            }
            reloadAll()
        }
    }

    private fun toggleGroupScene(group: String, sceneKey: String) {
        viewModelScope.launch {
            val map = withContext(Dispatchers.IO) {
                repository.toggleSceneForGroup(group, sceneKey)
                repository.getSceneGroupMap()
            }
            _uiState.update { it.copy(sceneGroupMap = map) }
        }
    }

    private fun restoreBuiltins() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ShareCardGenerator.getOrCreateBuiltinTemplates()
            }
            reloadAll()
            _effects.tryEmit(ShareCardManageEffect.ShowToast("已恢复内置模板"))
        }
    }

    private suspend fun reloadAll() {
        val selectedGroup = _uiState.value.selectedGroup
        val groups: List<String>
        val templates: List<ShareCardTemplate>
        val sceneMap: Map<String, List<String>>
        withContext(Dispatchers.IO) {
            groups = repository.getDistinctGroupNames()
            templates = if (selectedGroup == null) {
                repository.getAll()
            } else {
                repository.getByGroupName(selectedGroup)
            }
            sceneMap = repository.getSceneGroupMap()
        }
        _uiState.update {
            it.copy(
                groups = groups.toImmutableList(),
                templates = templates.toImmutableList(),
                sceneGroupMap = sceneMap,
            )
        }
    }
}
