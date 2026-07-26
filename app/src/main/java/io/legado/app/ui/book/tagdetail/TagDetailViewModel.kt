package io.legado.app.ui.book.tagdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
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
    }

    fun sendEvent(intent: TagDetailIntent) {
        when (intent) {
            is TagDetailIntent.Save -> viewModelScope.launch { save(intent) }
            TagDetailIntent.Delete -> viewModelScope.launch { delete() }
            TagDetailIntent.Refresh -> load()
            is TagDetailIntent.OpenBook ->
                viewModelScope.launch { _effect.emit(TagDetailEffect.NavigateToBook(it.bookUrl)) }
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
            val groups = appDb.bookTagGroupDao.all().toImmutableList()
            val books = if (tag != null) {
                val relations = appDb.bookTagRelationDao.getByTagId(tagId)
                relations.mapNotNull { appDb.bookDao.getBook(it.bookUrl) }.toImmutableList()
            } else {
                kotlinx.collections.immutable.persistentListOf()
            }
            _uiState.value = _uiState.value.copy(
                tag = tag,
                groupName = groupName,
                groups = groups,
                books = books,
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
        load()
    }

    private suspend fun delete() {
        appDb.bookTagRelationDao.deleteByTagId(tagId)
        appDb.bookTagDao.deleteById(tagId)
        _effect.emit(TagDetailEffect.Back)
    }
}
