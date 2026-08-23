package io.legado.app.domain.usecase

import io.legado.app.constant.BookType
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.repository.BookRepository
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.help.book.formTypeMask
import io.legado.app.help.book.removeType
import io.legado.app.help.book.TagManager

class AddToBookshelfUseCase(
    private val bookRepository: BookRepository,
    private val readingMemoryRepository: ReadingMemoryRepository,
    private val bookshelfSettingsGateway: BookshelfSettingsGateway,
    private val changeBookSourceUseCase: ChangeBookSourceUseCase,
) {

    suspend fun execute(book: SearchBook) {
        val b = book.toBook()
        b.removeType(BookType.notShelf)
        // 不允许同名同作者同形态时，一键添加视为替换在架那本：不联网取目录，保持瞬时且不会失败
        if (!bookshelfSettingsGateway.currentSettings.allowSameNameAuthorType) {
            val conflict = bookRepository.getShelfBookConflict(b.name, b.author, b.formTypeMask)
            if (conflict != null && conflict.bookUrl != b.bookUrl) {
                changeBookSourceUseCase.replaceShelfBookWithoutToc(conflict, b)
                // 标签与记忆已在替换里改绑到新 bookUrl，这里只刷新汇总
                readingMemoryRepository.ensureMemory(b.bookUrl)
                return
            }
        }
        if (b.order == 0) {
            b.order = bookRepository.getMinOrder() - 1
        }
        bookRepository.insert(b)
        // 加入书架即按书源分类生成标签关联（幂等，不与标签管理页重复）
        TagManager.generateTagsFromKind(b)
        // 换源重新添加导致 bookUrl 变化时，先认领旧的孤立记忆，避免同名同作者出现两条
        readingMemoryRepository.adoptOrphanMemory(b)
        // 加入书架即自动生成阅读记忆（数据汇总自 Book/ReadRecord/含笔记书签，幂等）
        readingMemoryRepository.ensureMemory(b.bookUrl)
    }
}
