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
import io.legado.app.utils.postEvent
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

            TagManagementIntent.Refresh -> loadData()

            is TagManagementIntent.OpenTagDetail ->
                viewModelScope.launch {
                    _effect.emit(TagManagementEffect.NavigateToTagDetail(intent.tagId))
                }

            is TagManagementIntent.SaveTag -> viewModelScope.launch { saveTag(intent) }
            is TagManagementIntent.DeleteTag -> viewModelScope.launch { deleteTag(intent.tag) }
            is TagManagementIntent.SaveGroup -> viewModelScope.launch { saveGroup(intent) }
            is TagManagementIntent.DeleteGroup -> viewModelScope.launch { deleteGroup(intent.group) }
            is TagManagementIntent.DeleteMapping -> viewModelScope.launch { deleteMapping(intent.mapping) }
            is TagManagementIntent.ExcludeTag -> viewModelScope.launch { excludeTag(intent) }
        }
    }

    // 标签来自书籍分类(kind)：首次打开标签管理页时，自动从书架书籍的 kind 生成标签，
    // 与 readdai 在书架渲染时调用 TagManager.generateTagsFromKind 的行为对齐。
    private var tagsSynced = false

    private suspend fun syncTagsFromBooks() {
        try {
            val books = appDb.bookDao.all
            for (book in books) {
                TagManager.generateTagsFromKind(book, postEvent = false)
            }
        } catch (_: Exception) {
            // 自动从书籍分类生成标签失败不影响标签读取
        }
    }

    private fun loadData() {
        viewModelScope.launch { loadDataBody() }
    }

    private suspend fun loadDataBody() = withContext(Dispatchers.IO) {
            if (!tagsSynced) {
                tagsSynced = true
                syncTagsFromBooks()
            }
            val tags = appDb.bookTagDao.observeAll().first()
            val tagCounts = TagManager.getTagBookCounts()
            val groupTagCounts = tags.groupingBy { it.groupId }.eachCount()
            val groups = appDb.bookTagGroupDao.getAllSorted()
            val mappings = appDb.tagMappingDao.getAll()
            _uiState.value = _uiState.value.copy(
                tags = tags.toImmutableList(),
                tagCounts = tagCounts,
                groupTagCounts = groupTagCounts,
                groups = groups.toImmutableList(),
                mappings = mappings.toImmutableList(),
            )
        }
    }

    private suspend fun saveTag(intent: TagManagementIntent.SaveTag) {
        val name = intent.name.trim()
        if (name.isBlank()) {
            _effect.emit(TagManagementEffect.ShowMessage("标签名不能为空"))
            return
        }
        // 改名后若与已有标签重名，则归并到该标签（合并书籍关联、删除本标签），
        // 等价于原来的「异名归一」，但无需单独的界面，直接改名保存即可。
        val existing = appDb.bookTagDao.getByName(name)
        if (intent.id == 0L) {
            if (existing != null) {
                _effect.emit(TagManagementEffect.ShowMessage("标签「$name」已存在"))
                return
            }
            appDb.bookTagDao.insert(
                BookTag(name = name, groupId = intent.groupId, color = intent.color),
            )
        } else {
            val old = appDb.bookTagDao.getById(intent.id) ?: return
            if (existing != null && existing.id != old.id) {
                TagManager.mergeAliasTagInto(old.name, existing.id)
                _effect.emit(TagManagementEffect.ShowMessage("已归并到「$name」"))
            } else {
                appDb.bookTagDao.update(
                    old.copy(
                        name = name,
                        groupId = intent.groupId,
                        color = intent.color,
                        updateTime = System.currentTimeMillis(),
                    ),
                )
            }
        }
        loadData()
        postEvent(EventBus.TAGS_UPDATED, intent.id)
    }

    private suspend fun deleteTag(tag: BookTag) {
        appDb.bookTagRelationDao.deleteByTagId(tag.id)
        appDb.tagMappingDao.deleteByNewTagId(tag.id)
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

    private suspend fun deleteMapping(mapping: TagMapping) {
        appDb.tagMappingDao.deleteById(mapping.id)
        loadData()
    }

    private suspend fun excludeTag(intent: TagManagementIntent.ExcludeTag) {
        val name = intent.name.trim()
        if (name.isBlank()) {
            _effect.emit(TagManagementEffect.ShowMessage("标签名不能为空"))
            return
        }
        appDb.excludedTagDao.insert(ExcludedTag(name = name, isRegex = false))
        _effect.emit(TagManagementEffect.ShowMessage("已添加到排除列表"))
    }
}
