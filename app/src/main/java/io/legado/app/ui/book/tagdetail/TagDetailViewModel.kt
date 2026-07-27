package io.legado.app.ui.book.tagdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.entities.TagMapping
import io.legado.app.help.book.TagManager
import io.legado.app.utils.eventBus.FlowEventBus
import io.legado.app.utils.postEvent
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TagDetailViewModel(private val tagId: Long) : ViewModel() {

    private val _uiState = MutableStateFlow(TagDetailUiState())
    val uiState = _uiState.asStateFlow()

    private val _effect = MutableSharedFlow<TagDetailEffect>()
    val effect: SharedFlow<TagDetailEffect> = _effect.asSharedFlow()

    init {
        load()
        viewModelScope.launch {
            FlowEventBus.with<Any>(EventBus.TAGS_UPDATED).collect {
                load()
            }
        }
    }

    fun sendEvent(intent: TagDetailIntent) {
        when (intent) {
            is TagDetailIntent.Save -> viewModelScope.launch { save(intent) }
            TagDetailIntent.Delete -> viewModelScope.launch { delete() }
            TagDetailIntent.Refresh -> load()
            is TagDetailIntent.OpenBook ->
                viewModelScope.launch { _effect.emit(TagDetailEffect.NavigateToBook(intent.bookUrl)) }
            is TagDetailIntent.SetStandard -> viewModelScope.launch { setStandard(intent) }
            is TagDetailIntent.RemoveAlias -> viewModelScope.launch { removeAlias(intent) }
        }
    }

    private fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val tag = appDb.bookTagDao.getById(tagId)
            val group = if (tag != null && tag.groupId != 0L) {
                appDb.bookTagGroupDao.getById(tag.groupId)
            } else {
                null
            }
            val groupName = group?.name ?: "未分组"
            val groups = appDb.bookTagGroupDao.getAllSorted().toImmutableList()
            val books = if (tag != null) {
                val relations = appDb.bookTagRelationDao.getByTagId(tagId)
                relations.mapNotNull { appDb.bookDao.getBook(it.bookUrl) }.toImmutableList()
            } else {
                kotlinx.collections.immutable.persistentListOf()
            }
            val mappings = appDb.tagMappingDao.getByNewTagId(tagId).toImmutableList()
            _uiState.value = _uiState.value.copy(
                tag = tag,
                groupName = groupName,
                groups = groups,
                books = books,
                mappings = mappings,
            )
        }
    }

    private suspend fun save(intent: TagDetailIntent.Save) {
        val old = appDb.bookTagDao.getById(tagId) ?: return
        appDb.bookTagDao.update(
            old.copy(
                name = intent.name,
                groupId = intent.groupId,
                color = intent.color,
                updateTime = System.currentTimeMillis(),
            ),
        )
        // 通知后台的标签管理页立即刷新（无需退出重进）
        postEvent(EventBus.TAGS_UPDATED, old.id)
        load()
    }

    private suspend fun delete() {
        appDb.bookTagRelationDao.deleteByTagId(tagId)
        appDb.tagMappingDao.deleteByNewTagId(tagId)
        appDb.bookTagDao.deleteById(tagId)
        postEvent(EventBus.TAGS_UPDATED, tagId)
        _effect.emit(TagDetailEffect.Back)
    }

    private suspend fun setStandard(intent: TagDetailIntent.SetStandard) {
        val standardName = intent.standardName.trim()
        if (standardName.isBlank()) return
        val currentTag = appDb.bookTagDao.getById(tagId) ?: return
        if (standardName == currentTag.name) {
            _effect.emit(TagDetailEffect.ShowMessage("不能映射到自身"))
            return
        }
        // 标准标签不存在则创建（继承当前标签的分组与颜色）
        val standardTag = appDb.bookTagDao.getByName(standardName) ?: run {
            val newId = appDb.bookTagDao.insert(
                BookTag(name = standardName, groupId = currentTag.groupId, color = currentTag.color),
            )
            appDb.bookTagDao.getById(newId) ?: return
        }
        // 若该标签已作为异名映射到其它标准标签，先解除旧映射
        appDb.tagMappingDao.getByOldTagName(currentTag.name)?.let {
            appDb.tagMappingDao.deleteById(it.id)
        }
        // 将本标签（别名）的书籍关联合并进标准标签，并删除本标签本身
        TagManager.mergeAliasTagInto(currentTag.name, standardTag.id)
        appDb.tagMappingDao.insert(
            TagMapping(oldTagName = currentTag.name, newTagId = standardTag.id),
        )
        postEvent(EventBus.TAGS_UPDATED, standardTag.id)
        _effect.emit(TagDetailEffect.Back)
    }

    private suspend fun removeAlias(intent: TagDetailIntent.RemoveAlias) {
        val mapping = appDb.tagMappingDao.getByOldTagName(intent.oldName) ?: return
        appDb.tagMappingDao.deleteById(mapping.id)
        postEvent(EventBus.TAGS_UPDATED, tagId)
        load()
    }
}
