package io.legado.app.domain.usecase

import io.legado.app.constant.BookType
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.repository.BookRepository
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.help.book.removeType
import io.legado.app.help.book.TagManager

class AddToBookshelfUseCase(
    private val bookRepository: BookRepository,
    private val readingMemoryRepository: ReadingMemoryRepository,
) {

    suspend fun execute(book: SearchBook) {
        val b = book.toBook()
        b.removeType(BookType.notShelf)
        if (b.order == 0) {
            b.order = bookRepository.getMinOrder() - 1
        }
        bookRepository.insert(b)
        // 加入书架即按书源分类生成标签关联（幂等，不与标签管理页重复）
        TagManager.generateTagsFromKind(b)
        // 加入书架即自动生成阅读记忆（数据汇总自 Book/ReadRecord/含笔记书签，幂等）
        readingMemoryRepository.ensureMemory(b.bookUrl)
    }
}
