package io.legado.app.ui.book.tagmanage

import android.database.sqlite.SQLiteConstraintException
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        viewModelScope.launch(Dispatchers.IO) { loadDataBody() }
    }

    private suspend fun loadDataBody() = withContext(Dispatchers.IO) {
            if (!tagsSynced) {
                tagsSynced = true
                syncTagsFromBooks()
            }
            val tags = appDb.bookTagDao.getAllSync()
            val tagCounts = TagManager.getTagBookCounts()
            val groupTagCounts = tags.groupingBy { it.groupId }.eachCount()
            val groups = appDb.bookTagGroupDao.getAllSorted()
            val mappings = appDb.tagMappingDao.getAll()
            _uiState.value = _uiState.value.copy(
                version = System.currentTimeMillis(),
                tags = tags.toImmutableList(),
                tagCounts = tagCounts,
                groupTagCounts = groupTagCounts,
                groups = groups.toImmutableList(),
                mappings = mappings.toImmutableList(),
            )
        }

    private suspend fun saveTag(intent: TagManagementIntent.SaveTag) {
        val name = intent.name.trim()
        if (name.isBlank()) {
            _effect.emit(TagManagementEffect.ShowMessage("标签名不能为空"))
            return
        }
        // 改名后若与已有标签重名，则归并到该标签（合并书籍关联、删除本标签），
        // 等价于原来的「异名归一」，但无需单独的界面，直接改名保存即可。
        // 无论改名还是归并，都要写入「旧名 -> 标准标签」的映射，否则退出标签管理后
        // 重新从书籍 kind 同步时会把旧名当成新标签重建出来（改名不生效）。
        val existing = appDb.bookTagDao.getByName(name)
        if (intent.id == 0L) {
            if (existing != null) {
                _effect.emit(TagManagementEffect.ShowMessage("标签「$name」已存在"))
                return
            }
            try {
                appDb.bookTagDao.insert(
                    BookTag(name = name, groupId = intent.groupId, color = intent.color),
                )
            } catch (e: SQLiteConstraintException) {
                _effect.emit(TagManagementEffect.ShowMessage("标签「$name」已存在"))
                return
            }
        } else {
            val old = appDb.bookTagDao.getById(intent.id) ?: return
            if (existing != null && existing.id != old.id) {
                TagManager.mergeAliasTagInto(old.name, existing.id)
                upsertMapping(old.name, existing.id)
                // 归并后把旧名写进书籍 kind/customTag，使书架、阅读记忆同步
                TagManager.rewriteTagInBooks(old.name, name)
                _effect.emit(TagManagementEffect.ShowMessage("已归并到「$name」"))
            } else {
                try {
                    appDb.bookTagDao.update(
                        old.copy(
                            name = name,
                            groupId = intent.groupId,
                            color = intent.color,
                            updateTime = System.currentTimeMillis(),
                        ),
                    )
                    if (old.name != name) {
                        upsertMapping(old.name, old.id)
                        // 改名后把旧名写进书籍 kind/customTag，使书架、阅读记忆同步
                        TagManager.rewriteTagInBooks(old.name, name)
                    }
                } catch (e: SQLiteConstraintException) {
                    val conflict = appDb.bookTagDao.getByName(name)
                    if (conflict != null && conflict.id != old.id) {
                        TagManager.mergeAliasTagInto(old.name, conflict.id)
                        upsertMapping(old.name, conflict.id)
                        // 归并后把旧名写进书籍 kind/customTag，使书架、阅读记忆同步
                        TagManager.rewriteTagInBooks(old.name, name)
                        _effect.emit(TagManagementEffect.ShowMessage("已归并到「$name」"))
                    } else {
                        throw e
                    }
                }
            }
        }
        loadDataBody()
        postEvent(EventBus.TAGS_UPDATED, intent.id)
    }

    /** 写入「旧标签名 -> 标准标签」映射：使用 REPLACE 直接插入，DA0 已有 OnConflictStrategy.REPLACE */
    private suspend fun upsertMapping(oldTagName: String, newTagId: Long) {
        if (oldTagName.isBlank()) return
        try {
            appDb.tagMappingDao.insert(TagMapping(oldTagName = oldTagName, newTagId = newTagId))
        } catch (e: SQLiteConstraintException) {
            appDb.tagMappingDao.getByOldTagName(oldTagName)?.let {
                appDb.tagMappingDao.deleteById(it.id)
            }
            appDb.tagMappingDao.insert(TagMapping(oldTagName = oldTagName, newTagId = newTagId))
        }
    }

    private suspend fun deleteTag(tag: BookTag) {
        appDb.bookTagRelationDao.deleteByTagId(tag.id)
        appDb.tagMappingDao.deleteByNewTagId(tag.id)
        appDb.bookTagDao.deleteById(tag.id)
        loadDataBody()
        postEvent(EventBus.TAGS_UPDATED, tag.id)
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
        loadDataBody()
        postEvent(EventBus.TAGS_UPDATED, intent.id)
    }

    private suspend fun deleteGroup(group: BookTagGroup) {
        appDb.bookTagGroupDao.deleteById(group.id)
        loadDataBody()
        postEvent(EventBus.TAGS_UPDATED, group.id)
    }

    private suspend fun deleteMapping(mapping: TagMapping) {
        appDb.tagMappingDao.deleteById(mapping.id)
        loadDataBody()
        postEvent(EventBus.TAGS_UPDATED, mapping.id)
    }

    private suspend fun excludeTag(intent: TagManagementIntent.ExcludeTag) {
        val name = intent.name.trim()
        if (name.isBlank()) {
            _effect.emit(TagManagementEffect.ShowMessage("标签名不能为空"))
            return
        }
        appDb.excludedTagDao.insert(ExcludedTag(name = name, isRegex = false))
        loadDataBody()
        postEvent(EventBus.TAGS_UPDATED, name)
        _effect.emit(TagManagementEffect.ShowMessage("已添加到排除列表"))
    }
}
