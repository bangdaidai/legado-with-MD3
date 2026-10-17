package io.legado.app.domain.usecase

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.repository.BookRepository
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.domain.model.BookshelfConflict
import io.legado.app.help.book.formTypeMask
import io.legado.app.help.book.removeType
import io.legado.app.help.book.TagManager

sealed interface AddToBookshelfResult {
    /** 可以直接加入书架。 */
    data object Added : AddToBookshelfResult

    /** 命中和当前同一作品的重复书，需要用户选择共存还是迁移。 */
    data class Conflict(val conflict: BookshelfConflict) : AddToBookshelfResult
}

class AddToBookshelfUseCase(
    private val bookRepository: BookRepository,
    private val readingMemoryRepository: ReadingMemoryRepository,
    private val bookshelfSettingsGateway: BookshelfSettingsGateway,
    private val changeBookSourceUseCase: ChangeBookSourceUseCase,
    private val findBookshelfConflictUseCase: FindBookshelfConflictUseCase,
) {

    suspend fun execute(book: SearchBook): AddToBookshelfResult {
        // 同名同作者命中时让上层弹冲突 Sheet，由用户选择共存或迁移
        val b = book.toBook()
        val conflict = findBookshelfConflictUseCase.execute(b)
        if (conflict != null) {
            return AddToBookshelfResult.Conflict(conflict)
        }
        addToShelf(b)
        return AddToBookshelfResult.Added
    }

    /**
     * 阅读器内确认加入书架：书与目录已在库，与一键添加共用同一套「同名同作者同形态替换」规则，
     * 避免设置关闭时又插出一本同名书。
     */
    suspend fun execute(book: Book): Book = addToShelf(book)

    private suspend fun addToShelf(b: Book): Book {
        b.removeType(BookType.notShelf)
        // 不允许同名同作者同形态时，加入书架视为替换在架那本：不联网取目录，保持瞬时且不会失败
        if (!bookshelfSettingsGateway.currentSettings.allowSameNameAuthorType) {
            val conflict = bookRepository.getShelfBookConflict(b.name, b.author, b.formTypeMask)
            if (conflict != null && conflict.bookUrl != b.bookUrl) {
                changeBookSourceUseCase.replaceShelfBookWithoutToc(conflict, b)
                // 标签与记忆已在替换里改绑到新 bookUrl，这里只刷新汇总
                readingMemoryRepository.ensureMemory(b.bookUrl)
                return b
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
        return b
    }
}
