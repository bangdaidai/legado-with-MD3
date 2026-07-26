package io.legado.app.ui.book.tagdetail

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookTag
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class TagDetailUiState(
    val tag: BookTag? = null,
    val groupName: String = "",
    val books: ImmutableList<Book> = persistentListOf(),
)

sealed interface TagDetailIntent {
    data class Save(val name: String, val groupId: Long, val color: Long) : TagDetailIntent
    data object Delete : TagDetailIntent
    data object Refresh : TagDetailIntent
}

sealed interface TagDetailEffect {
    data object Back : TagDetailEffect
    data class ShowMessage(val msg: String) : TagDetailEffect
}
