package io.legado.app.ui.book.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.repository.BookTagGroupRepository
import io.legado.app.data.repository.BookTagRepository
import io.legado.app.help.book.TagManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 标签库管理 ViewModel（F1）
 */
class BookTagManageViewModel : ViewModel() {

    val groups: Flow<List<BookTagGroup>> = BookTagGroupRepository.observeAll()
    val tags: Flow<List<BookTag>> = BookTagRepository.observeAll()

    private val _selectedGroupId = MutableStateFlow<Long?>(null)
    val selectedGroupId: StateFlow<Long?> = _selectedGroupId

    fun setSelectedGroup(id: Long?) {
        _selectedGroupId.value = id
    }

    fun filteredTags(all: List<BookTag>, selectedGroupId: Long?): List<BookTag> {
        return if (selectedGroupId == null) all
        else all.filter { it.groupId == selectedGroupId }
    }

    fun saveTag(tag: BookTag) {
        viewModelScope.launch {
            if (tag.id == 0L) BookTagRepository.insert(tag)
            else BookTagRepository.update(tag)
        }
    }

    fun deleteTag(tag: BookTag) {
        viewModelScope.launch { BookTagRepository.delete(tag) }
    }

    /** 确保分组存在，返回其 id（用于标签保存时绑定分组） */
    suspend fun createGroup(name: String): Long {
        val trimmed = name.trim()
        val exist = BookTagGroupRepository.getByName(trimmed)
        if (exist != null) return exist.id
        val order = (System.currentTimeMillis() % 100000).toInt()
        return BookTagGroupRepository.insert(BookTagGroup(name = trimmed, sortOrder = order))
    }

    /** 删除分组：先把其下标签解绑到“未分组”，再删除分组 */
    fun deleteGroup(group: BookTagGroup) {
        viewModelScope.launch {
            BookTagRepository.observeAll().first()
                .filter { it.groupId == group.id }
                .forEach { BookTagRepository.update(it.copy(groupId = 0)) }
            BookTagGroupRepository.delete(group)
        }
    }

    /** 进入标签库时调用：从书籍分类自动生成标签与关联 */
    fun syncFromBooks() {
        viewModelScope.launch { TagManager.syncTagsFromBooks() }
    }
}
