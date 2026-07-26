package io.legado.app.ui.book.tagdetail

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class TagDetailUiState(
    val tag: BookTag? = null,
    val groupName: String = "",
    val groups: ImmutableList<BookTagGroup> = persistentListOf(),
    val books: ImmutableList<Book> = persistentListOf(),
)

sealed interface TagDetailIntent {
    data class Save(val name: String, val groupId: Long, val color: Long) : TagDetailIntent
    data object Delete : TagDetailIntent
    data object Refresh : TagDetailIntent
    data class OpenBook(val bookUrl: String) : TagDetailIntent
}

sealed interface TagDetailEffect {
    data object Back : TagDetailEffect
    data class ShowMessage(val msg: String) : TagDetailEffect
    data class NavigateToBook(val bookUrl: String) : TagDetailEffect
}
