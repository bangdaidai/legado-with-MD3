package io.legado.app.ui.book.tag

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.entities.ExcludedTag
import io.legado.app.data.repository.BookTagGroupRepository
import io.legado.app.data.repository.BookTagRepository
import io.legado.app.data.repository.ExcludedTagRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 标签库管理页 ViewModel：维护 标签 / 分组 / 排除标签 三类数据。
 */
class TagManagementViewModel(application: Application) : BaseViewModel(application) {

    val tags: StateFlow<List<BookTag>> = BookTagRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groups: StateFlow<List<BookTagGroup>> = BookTagGroupRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val excludedTags: StateFlow<List<ExcludedTag>> = ExcludedTagRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveTag(tag: BookTag) {
        viewModelScope.launch {
            if (tag.id == 0L) BookTagRepository.insert(tag) else BookTagRepository.update(tag)
        }
    }

    fun deleteTag(tag: BookTag) {
        viewModelScope.launch { BookTagRepository.delete(tag) }
    }

    suspend fun createGroup(name: String): Long =
        BookTagGroupRepository.insert(BookTagGroup(name = name.trim()))

    fun deleteGroup(group: BookTagGroup) {
        viewModelScope.launch { BookTagGroupRepository.delete(group) }
    }

    fun addExcluded(name: String) {
        viewModelScope.launch { ExcludedTagRepository.add(name.trim()) }
    }

    fun removeExcluded(name: String) {
        viewModelScope.launch { ExcludedTagRepository.remove(name) }
    }
}
