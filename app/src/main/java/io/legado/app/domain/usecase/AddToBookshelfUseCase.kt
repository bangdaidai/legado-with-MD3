package io.legado.app.domain.usecase

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.repository.BookRepository
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.domain.model.BookshelfConflict
import io.legado.app.domain.usecase.ChangeBookSourceUseCase
import io.legado.app.help.book.TagManager
import io.legado.app.help.book.VideoBookPreloader
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.removeType

sealed interface AddToBookshelfResult {
    /** 已直接加入书架。 */
    data object Added : AddToBookshelfResult

    /** 发现疑似同一作品，需要用户先选择共存还是迁移。 */
    data class Conflict(val conflict: BookshelfConflict) : AddToBookshelfResult
}

class AddToBookshelfUseCase(
    private val bookRepository: BookRepository,
    private val findBookshelfConflictUseCase: FindBookshelfConflictUseCase,
    // fork 的加架链路依赖：同名替换冲突判断 + 标签/记忆改绑
    private val bookshelfSettingsGateway: BookshelfSettingsGateway,
    private val changeBookSourceUseCase: ChangeBookSourceUseCase,
    private val readingMemoryRepository: ReadingMemoryRepository,
) {

    suspend fun execute(book: SearchBook): AddToBookshelfResult {
        val b = book.toBook()
        b.removeType(BookType.notShelf)
        val conflict = findBookshelfConflictUseCase.execute(b)
        if (conflict != null) {
            return AddToBookshelfResult.Conflict(conflict)
        }
        if (b.order == 0) {
            b.order = bookRepository.getMinOrder() - 1
        }
        bookRepository.insert(b)
        if (b.isVideo) {
            // 视频书加入书架后先把详情/目录补齐，播放页打开时就不必再等
            VideoBookPreloader.preload(b.bookUrl)
        }
        return AddToBookshelfResult.Added
    }

    /** fork：在架书籍（Book 类型）的加架/替换链路，AudioPlay 与详情页 ViewModel 在用。 */
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
