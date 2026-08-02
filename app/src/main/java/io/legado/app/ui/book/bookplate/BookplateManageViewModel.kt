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

    private val defaultGroups = listOf(
        BookplateTemplate.DEFAULT_GROUP_BOOK,
        BookplateTemplate.DEFAULT_GROUP_STATS,
        BookplateTemplate.DEFAULT_GROUP_ANNOTATION,
    )

    init {
        onIntent(BookplateManageIntent.Load)
    }

    fun onIntent(intent: BookplateManageIntent) {
        when (intent) {
            is BookplateManageIntent.Load -> load()
            is BookplateManageIntent.SelectGroup -> selectGroup(intent.group)
            is BookplateManageIntent.SelectTemplate -> selectTemplate(intent.id)
            is BookplateManageIntent.StartEdit -> _uiState.update { it.copy(editing = intent.template ?: BookplateTemplate(groupName = it.selectedGroup)) }
            is BookplateManageIntent.CancelEdit -> _uiState.update { it.copy(editing = null) }
            is BookplateManageIntent.SaveTemplate -> saveTemplate(intent.name, intent.html)
            is BookplateManageIntent.DeleteTemplate -> deleteTemplate(intent.template)
            is BookplateManageIntent.RestoreBuiltins -> restoreBuiltins()
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            withContext(Dispatchers.IO) {
                // 确保内置模板存在
                BookplateGenerator.getOrCreateBuiltinTemplates()
            }
            val group = _uiState.value.selectedGroup
            val (groups, templates) = withContext(Dispatchers.IO) {
                val existing = repository.getDistinctGroupNames()
                val merged = (defaultGroups + existing).distinct()
                merged to repository.getByGroupName(group)
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

    private fun selectGroup(group: String) {
        if (group == _uiState.value.selectedGroup) return
        _uiState.update { it.copy(selectedGroup = group, templates = persistentListOf()) }
        viewModelScope.launch {
            val templates = withContext(Dispatchers.IO) {
                repository.getByGroupName(group)
            }
            _uiState.update { it.copy(templates = templates.toImmutableList()) }
        }
    }

    private fun selectTemplate(id: Long) {
        _uiState.update { it.copy(selectedTemplateId = id) }
        repository.setSelectedTemplateId(id)
    }

    private fun saveTemplate(name: String, html: String) {
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
                    groupName = editing.groupName.ifBlank { _uiState.value.selectedGroup },
                )
                if (toSave.id == 0L) {
                    repository.insert(toSave)
                } else {
                    repository.update(toSave)
                }
            }
            _uiState.update { it.copy(editing = null) }
            reloadTemplates()
        }
    }

    private fun deleteTemplate(template: BookplateTemplate) {
        if (template.isBuiltin) {
            _effects.tryEmit(BookplateManageEffect.ShowToast("内置模板不可删除"))
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repository.delete(template)
            }
            reloadTemplates()
        }
    }

    private fun restoreBuiltins() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                BookplateGenerator.getOrCreateBuiltinTemplates()
            }
            reloadTemplates()
            _effects.tryEmit(BookplateManageEffect.ShowToast("已恢复内置模板"))
        }
    }

    private suspend fun reloadTemplates() {
        val group = _uiState.value.selectedGroup
        val templates = withContext(Dispatchers.IO) {
            repository.getByGroupName(group)
        }
        _uiState.update { it.copy(templates = templates.toImmutableList()) }
    }
}
