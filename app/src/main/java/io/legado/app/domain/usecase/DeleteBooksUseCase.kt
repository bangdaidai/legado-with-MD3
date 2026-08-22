package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.BookSourceCallbackGateway
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.gateway.LocalBookGateway
import io.legado.app.domain.repository.BookDomainRepository

class DeleteBooksUseCase(
    private val bookRepository: BookDomainRepository,
    private val localBookGateway: LocalBookGateway,
    private val bookSourceCallbackGateway: BookSourceCallbackGateway,
    private val chapterSpeechGateway: ChapterSpeechGateway,
) {

    suspend fun execute(bookUrls: Set<String>, deleteOriginal: Boolean): List<String> {
        if (bookUrls.isEmpty()) return emptyList()
        val books = bookRepository.getDeletableBooks(bookUrls)
        books.forEach { book ->
            if (book.isLocal) {
                localBookGateway.deleteBook(book.bookUrl, deleteOriginal)
            } else {
                bookSourceCallbackGateway.onDeleteFromShelf(book.bookUrl)
            }
            bookRepository.deleteChaptersByBook(book.bookUrl)
            // 分镜跟着章节一起走，这条路径不经过 Book.delete()，得自己清
            chapterSpeechGateway.deleteBook(book.bookUrl)
        }
        bookRepository.deleteBooks(books.map { it.bookUrl }.toSet())
        return books.map { it.bookUrl }
    }
}
