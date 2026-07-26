package io.legado.app.ui.book.tagmanage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.entities.ExcludedTag
import io.legado.app.data.entities.TagMapping
import io.legado.app.help.book.TagManager
import io.legado.app.utils.eventBus.FlowEventBus
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class TagManagementViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(TagManagementUiState())
    val uiState = _uiState.asStateFlow()

    private val _effect = MutableSharedFlow<TagManagementEffect>()
    val effect: SharedFlow<TagManagementEffect> = _effect.asSharedFlow()

    init {
        loadData()
        // 标签变化（生成/删除）后刷新
        viewModelScope.launch {
            FlowEventBus.with<Any>(EventBus.TAGS_UPDATED).collect {
                loadData()
            }
        }
    }

    fun sendEvent(intent: TagManagementIntent) {
        when (intent) {
            is TagManagementIntent.Search ->
                _uiState.value = _uiState.value.copy(searchQuery = intent.q)

            is TagManagementIntent.SelectTab ->
                _uiState.value = _uiState.value.copy(selectedTab = intent.index)

            TagManagementIntent.Refresh -> loadData()

            is TagManagementIntent.OpenTagDetail ->
                viewModelScope.launch {
                    _effect.emit(TagManagementEffect.NavigateToTagDetail(intent.tagId))
                }

            is TagManagementIntent.SaveTag -> viewModelScope.launch { saveTag(intent) }
            is TagManagementIntent.DeleteTag -> viewModelScope.launch { deleteTag(intent.tag) }
            is TagManagementIntent.SaveGroup -> viewModelScope.launch { saveGroup(intent) }
            is TagManagementIntent.DeleteGroup -> viewModelScope.launch { deleteGroup(intent.group) }
            is TagManagementIntent.SaveExcluded -> viewModelScope.launch { saveExcluded(intent) }
            is TagManagementIntent.DeleteExcluded -> viewModelScope.launch { deleteExcluded(intent.excluded) }
            is TagManagementIntent.SaveMapping -> viewModelScope.launch { saveMapping(intent) }
            is TagManagementIntent.DeleteMapping -> viewModelScope.launch { deleteMapping(intent.mapping) }
        }
    }

    private fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            val tags = appDb.bookTagDao.observeAll().first()
            val tagCounts = TagManager.getTagBookCounts()
            val groupTagCounts = tags.groupingBy { it.groupId }.eachCount()
            val groups = appDb.bookTagGroupDao.getAllSorted()
            val excluded = appDb.excludedTagDao.getAllSync()
            val mappings = appDb.tagMappingDao.getAll()
            _uiState.value = _uiState.value.copy(
                tags = tags.toImmutableList(),
                tagCounts = tagCounts,
                groupTagCounts = groupTagCounts,
                groups = groups.toImmutableList(),
                excludedTags = excluded.toImmutableList(),
                mappings = mappings.toImmutableList(),
            )
        }
    }

    private suspend fun saveTag(intent: TagManagementIntent.SaveTag) {
        if (intent.name.isBlank()) {
            _effect.emit(TagManagementEffect.ShowMessage("标签名不能为空"))
            return
        }
        if (intent.id == 0L) {
            appDb.bookTagDao.insert(
                BookTag(name = intent.name, groupId = intent.groupId, color = intent.color),
            )
        } else {
            val old = appDb.bookTagDao.getById(intent.id) ?: return
            appDb.bookTagDao.update(
                old.copy(
                    name = intent.name,
                    groupId = intent.groupId,
                    color = intent.color,
                    updateTime = System.currentTimeMillis(),
                ),
            )
        }
        loadData()
    }

    private suspend fun deleteTag(tag: BookTag) {
        appDb.bookTagRelationDao.deleteByTagId(tag.id)
        appDb.bookTagDao.deleteById(tag.id)
        loadData()
    }

    private suspend fun saveGroup(intent: TagManagementIntent.SaveGroup) {
        if (intent.name.isBlank()) {
            _effect.emit(TagManagementEffect.ShowMessage("分组名不能为空"))
            return
        }
        if (intent.id == 0L) {
            val maxSort = appDb.bookTagGroupDao.getMaxSortOrder()
            appDb.bookTagGroupDao.insert(BookTagGroup(name = intent.name, sortOrder = maxSort + 1))
        } else {
            val old = appDb.bookTagGroupDao.getById(intent.id) ?: return
            appDb.bookTagGroupDao.update(old.copy(name = intent.name))
        }
        loadData()
    }

    private suspend fun deleteGroup(group: BookTagGroup) {
        appDb.bookTagGroupDao.deleteById(group.id)
        loadData()
    }

    private suspend fun saveExcluded(intent: TagManagementIntent.SaveExcluded) {
        if (intent.name.isBlank()) {
            _effect.emit(TagManagementEffect.ShowMessage("排除项不能为空"))
            return
        }
        if (intent.isRegex && !TagManager.isValidRegex(intent.name)) {
            _effect.emit(TagManagementEffect.ShowMessage("正则格式不正确"))
            return
        }
        if (intent.id == 0L) {
            appDb.excludedTagDao.insert(
                ExcludedTag(name = intent.name, isRegex = intent.isRegex),
            )
        } else {
            val old = appDb.excludedTagDao.getById(intent.id) ?: return
            appDb.excludedTagDao.update(old.copy(name = intent.name, isRegex = intent.isRegex))
        }
        loadData()
    }

    private suspend fun deleteExcluded(excluded: ExcludedTag) {
        appDb.excludedTagDao.deleteById(excluded.id)
        loadData()
    }

    private suspend fun saveMapping(intent: TagManagementIntent.SaveMapping) {
        if (intent.oldTagName.isBlank() || intent.newTagId == 0L) {
            _effect.emit(TagManagementEffect.ShowMessage("请填写异名并选择标准标签"))
            return
        }
        if (intent.id == 0L) {
            appDb.tagMappingDao.insert(
                TagMapping(oldTagName = intent.oldTagName, newTagId = intent.newTagId),
            )
        } else {
            val old = appDb.tagMappingDao.getByOldTagName(intent.oldTagName) ?: return
            appDb.tagMappingDao.update(old.copy(newTagId = intent.newTagId))
        }
        loadData()
    }

    private suspend fun deleteMapping(mapping: TagMapping) {
        appDb.tagMappingDao.deleteById(mapping.id)
        loadData()
    }
}
