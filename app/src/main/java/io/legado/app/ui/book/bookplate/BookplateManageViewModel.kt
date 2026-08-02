package io.legado.app.ui.book.bookplate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.BookplateTemplate
import io.legado.app.data.repository.BookplateRepository
import io.legado.app.help.book.BookplateGenerator
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

class BookplateManageViewModel(
    private val repository: BookplateRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        BookplateManageUiState(
            selectedTemplateId = repository.getSelectedTemplateId()
        )
    )
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<BookplateManageEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    init {
        onIntent(BookplateManageIntent.Load)
    }

    fun onIntent(intent: BookplateManageIntent) {
        when (intent) {
            is BookplateManageIntent.Load -> load()
            is BookplateManageIntent.SelectGroup -> selectGroup(intent.group)
            is BookplateManageIntent.SelectTemplate -> selectTemplate(intent.id)
            is BookplateManageIntent.StartEdit -> _uiState.update {
                it.copy(editing = intent.template ?: BookplateTemplate(groupName = it.selectedGroup ?: ""))
            }
            is BookplateManageIntent.CancelEdit -> _uiState.update { it.copy(editing = null) }
            is BookplateManageIntent.SaveTemplate -> saveTemplate(intent.name, intent.html, intent.group)
            is BookplateManageIntent.RequestDelete -> _uiState.update { it.copy(deleteConfirm = intent.template) }
            is BookplateManageIntent.ConfirmDelete -> confirmDelete()
            is BookplateManageIntent.DismissDelete -> _uiState.update { it.copy(deleteConfirm = null) }
            is BookplateManageIntent.ShowGroupManage -> _uiState.update { it.copy(showGroupManage = true) }
            is BookplateManageIntent.DismissGroupManage -> _uiState.update { it.copy(showGroupManage = false) }
            is BookplateManageIntent.RenameGroup -> renameGroup(intent.oldName, intent.newName)
            is BookplateManageIntent.DeleteGroup -> deleteGroup(intent.group)
            is BookplateManageIntent.RestoreBuiltins -> restoreBuiltins()
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            withContext(Dispatchers.IO) {
                BookplateGenerator.getOrCreateBuiltinTemplates()
            }
            val selectedGroup = _uiState.value.selectedGroup
            val (groups, templates) = withContext(Dispatchers.IO) {
                val existing = repository.getDistinctGroupNames()
                val tpls = if (selectedGroup == null) {
                    repository.getAll()
                } else {
                    repository.getByGroupName(selectedGroup)
                }
                existing to tpls
            }
            _uiState.update {
                it.copy(
                    loading = false,
                    groups = groups.toImmutableList(),
                    templates = templates.toImmutableList(),
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

    private fun selectTemplate(id: Long) {
        _uiState.update { it.copy(selectedTemplateId = id) }
        repository.setSelectedTemplateId(id)
    }

    private fun saveTemplate(name: String, html: String, group: String) {
        val editing = _uiState.value.editing ?: return
        if (name.isBlank()) {
            _effects.tryEmit(BookplateManageEffect.ShowToast("名称不能为空"))
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
            }
            _uiState.update {
                it.copy(selectedGroup = if (it.selectedGroup == group) null else it.selectedGroup)
            }
            reloadAll()
        }
    }

    private fun restoreBuiltins() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                BookplateGenerator.getOrCreateBuiltinTemplates()
            }
            reloadAll()
            _effects.tryEmit(BookplateManageEffect.ShowToast("已恢复内置模板"))
        }
    }

    private suspend fun reloadAll() {
        val selectedGroup = _uiState.value.selectedGroup
        val (groups, templates) = withContext(Dispatchers.IO) {
            val existing = repository.getDistinctGroupNames()
            val tpls = if (selectedGroup == null) {
                repository.getAll()
            } else {
                repository.getByGroupName(selectedGroup)
            }
            existing to tpls
        }
        _uiState.update {
            it.copy(
                groups = groups.toImmutableList(),
                templates = templates.toImmutableList(),
            )
        }
    }
}
