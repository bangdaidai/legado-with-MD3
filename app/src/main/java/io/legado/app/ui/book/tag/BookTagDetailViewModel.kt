package io.legado.app.ui.book.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.repository.BookRepository
import io.legado.app.data.repository.BookTagGroupRepository
import io.legado.app.data.repository.BookTagRelationRepository
import io.legado.app.data.repository.BookTagRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 标签详情 ViewModel（F1）
 * 负责加载标签信息、所属分组名、以及该标签下的书籍列表。
 */
class BookTagDetailViewModel : ViewModel() {

    private val _tag = MutableStateFlow<BookTag?>(null)
    val tag: StateFlow<BookTag?> = _tag.asStateFlow()

    private val _groupName = MutableStateFlow("")
    val groupName: StateFlow<String> = _groupName.asStateFlow()

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    val groups: StateFlow<List<BookTagGroup>> = BookTagGroupRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun load(tagId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val t = BookTagRepository.get(tagId)
            _tag.value = t
            val gid = t?.groupId ?: 0L
            _groupName.value =
                if (gid == 0L) "未分组" else BookTagGroupRepository.get(gid)?.name ?: "未分组"
            val urls = BookTagRelationRepository.getBookUrlsByTag(tagId)
            _books.value = BookRepository.getByUrls(urls)
        }
    }

    suspend fun saveTag(tag: BookTag): Long {
        return if (tag.id == 0L) BookTagRepository.insert(tag)
        else {
            BookTagRepository.update(tag)
            tag.id
        }
    }

    suspend fun deleteTag(tag: BookTag) {
        BookTagRepository.delete(tag)
    }

    suspend fun createGroup(name: String): Long {
        return BookTagGroupRepository.insert(BookTagGroup(name = name))
    }
}
