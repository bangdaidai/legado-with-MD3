package io.legado.app.domain.usecase

import androidx.room.withTransaction
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.TagManager
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.removeType
import io.legado.app.model.ReadBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.utils.mapAsync
import io.legado.app.utils.postEvent
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import java.util.concurrent.atomic.AtomicInteger

data class ChangeSourceMigrationOptions(
    val migrateChapters: Boolean = true,
    val migrateReadingProgress: Boolean = true,
    val migrateGroup: Boolean = true,
    val migrateCover: Boolean = true,
    val migrateCategory: Boolean = true,
    val migrateRemark: Boolean = true,
    val migrateReadConfig: Boolean = true,
    val deleteDownloadedChapters: Boolean = false,
    val keepOfficialMeta: Boolean = true,
)

data class ChangeBookSourceResult(
    val oldBookUrl: String,
    val book: Book,
)

/**
 * 换源导致 bookUrl 变化时广播的「书籍被替换」事件（[io.legado.app.constant.EventBus.BOOK_REPLACED]）。
 * 换源等于删旧行插新行，仍持有旧 bookUrl 的页面收到后应改绑到 [newBookUrl]，而不是把旧 url 查不到当成不在书架。
 */
data class BookReplacedEvent(
    val oldBookUrl: String,
    val newBookUrl: String,
)

data class BatchChangeBookSourceResult(
    val changedCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
)

data class BatchChangeSourceCandidate(
    val source: BookSource,
    val book: Book,
    val chapterCount: Int,
)

data class BatchChangeSourcePreviewItem(
    val oldBook: Book,
    val candidates: List<BatchChangeSourceCandidate> = emptyList(),
    val selectedCandidateIndex: Int = 0,
    val status: BatchChangeSourcePreviewStatus = BatchChangeSourcePreviewStatus.NotFound,
) {
    val selectedCandidate: BatchChangeSourceCandidate?
        get() = candidates.getOrNull(selectedCandidateIndex)

    val canMigrate: Boolean
        get() = status == BatchChangeSourcePreviewStatus.Matched && selectedCandidate != null
}

enum class BatchChangeSourcePreviewStatus {
    Matched,
    NotFound,
    Skipped,
}

class ChangeBookSourceUseCase(
    private val database: AppDatabase,
    private val bookDao: BookDao,
    private val bookChapterDao: BookChapterDao,
    private val otherSettingsGateway: OtherSettingsGateway,
    private val readSettingsGateway: ReadSettingsGateway,
    private val bookKnowledgeGateway: BookKnowledgeGateway,
) {

    suspend fun applyMigration(
        oldBook: Book,
        newBook: Book,
        chapters: List<BookChapter>,
        options: ChangeSourceMigrationOptions,
    ): Book {
        oldBook.applyMigrationTo(
            newBook,
            chapters,
            options,
            otherSettingsGateway.currentSettings.replaceEnableDefault,
            readSettingsGateway.currentSettings.chineseConverterType,
            keepOfficialMeta = options.keepOfficialMeta,
        )
        newBook.removeType(BookType.updateError)
        return newBook
    }

    suspend fun changeTo(
        oldBook: Book,
        newBook: Book,
        chapters: List<BookChapter>,
        options: ChangeSourceMigrationOptions,
    ): ChangeBookSourceResult {
        val oldBookUrl = oldBook.bookUrl
        // 换源到本地文件：目录只存在于文件本身，不落库就没有章节；旧源的章节缓存会被
        // BookHelp.getContent 优先命中，搬过来会顶掉本地文件的正文，所以直接清掉
        val effectiveOptions = if (newBook.isLocal) {
            options.copy(migrateChapters = true, deleteDownloadedChapters = true)
        } else {
            options
        }
        applyMigration(oldBook, newBook, chapters, effectiveOptions)
        if (effectiveOptions.deleteDownloadedChapters) {
            BookHelp.clearCache(oldBook)
        } else if (oldBook.bookUrl != newBook.bookUrl) {
            BookHelp.updateCacheFolder(oldBook, newBook)
        }
        database.withTransaction {
            bookChapterDao.delByBook(oldBook.bookUrl)
            bookDao.delete(oldBook)
            bookDao.insert(newBook)
            if (effectiveOptions.migrateChapters) {
                bookChapterDao.insert(*chapters.toTypedArray())
            }
            if (oldBookUrl != newBook.bookUrl) {
                database.readingMemoryDao.migrateToNewBookUrl(oldBookUrl, newBook.bookUrl)
                database.readingMemoryDao.deleteMigrated(oldBookUrl)
                // 笔记高亮的渲染查询按 bookUrl 过滤，不跟着搬就会静默画不出来
                database.bookMarkingDao.migrateToNewBookUrl(oldBookUrl, newBook.bookUrl)
                // 迁移角色数据：保留旧角色，避免换源后丢失
                bookKnowledgeGateway.migrateToNewBookUrl(oldBookUrl, newBook.bookUrl)
            }
        }
        // 换源后重建新书的标签关联，并清理旧 bookUrl 上的孤儿关联，避免标签丢失。
        // generateTagsFromKind 是纯追加（已存在的关联会被跳过），所以新 bookUrl 上的旧关联
        // 必须先删掉：同 url 换源、或该 url 曾在书架上出现过时，上一个源的标签会残留并混进新 kind。
        if (oldBookUrl != newBook.bookUrl) {
            database.bookTagRelationDao.deleteByBookUrl(oldBookUrl)
        }
        TagManager.updateTagsOnSourceChange(newBook)
        if (effectiveOptions.migrateChapters) {
            ReadBook.onChapterListUpdated(newBook)
        }
        // bookUrl 变了就是一次书籍替换：旧行已被删除，任何仍持有旧 bookUrl 的页面必须改绑到新书
        if (oldBookUrl != newBook.bookUrl) {
            postEvent(EventBus.BOOK_REPLACED, BookReplacedEvent(oldBookUrl, newBook.bookUrl))
        }
        return ChangeBookSourceResult(oldBookUrl, newBook)
    }

    /**
     * 一键添加（搜索/发现/首页）命中「同名同作者同形态」时的轻量替换：不联网取目录，
     * 直接用新书替换在架的旧书，保证一键添加仍是瞬时且不会失败的操作。
     * 进度按章节序号原样带走——按标题对齐需要目录，留给之后刷新目录时自然修正。
     */
    suspend fun replaceShelfBookWithoutToc(oldBook: Book, newBook: Book): ChangeBookSourceResult {
        val oldBookUrl = oldBook.bookUrl
        newBook.durChapterIndex = oldBook.durChapterIndex
        newBook.durChapterPos = oldBook.durChapterPos
        newBook.durChapterTitle = oldBook.durChapterTitle
        newBook.durChapterTime = oldBook.durChapterTime
        newBook.totalChapterNum = oldBook.totalChapterNum
        newBook.group = oldBook.group
        newBook.order = oldBook.order
        newBook.customCoverUrl = oldBook.customCoverUrl
        newBook.customIntro = oldBook.customIntro
        newBook.customTag = oldBook.customTag
        newBook.remark = oldBook.remark
        newBook.readConfig = oldBook.readConfig
        newBook.canUpdate = oldBook.canUpdate
        newBook.removeType(BookType.updateError)
        if (oldBookUrl != newBook.bookUrl) {
            BookHelp.updateCacheFolder(oldBook, newBook)
        }
        database.withTransaction {
            bookChapterDao.delByBook(oldBookUrl)
            bookDao.delete(oldBook)
            bookDao.insert(newBook)
            if (oldBookUrl != newBook.bookUrl) {
                database.readingMemoryDao.migrateToNewBookUrl(oldBookUrl, newBook.bookUrl)
                database.readingMemoryDao.deleteMigrated(oldBookUrl)
                database.bookMarkingDao.migrateToNewBookUrl(oldBookUrl, newBook.bookUrl)
            }
        }
        if (oldBookUrl != newBook.bookUrl) {
            database.bookTagRelationDao.deleteByBookUrl(oldBookUrl)
        }
        TagManager.updateTagsOnSourceChange(newBook)
        // 阅读会话正指向这部作品时同步换掉，否则 saveRead 会把已删除的旧行写回去
        if (ReadBook.isCurrentBook(newBook)) {
            ReadBook.replaceCurrentBook(newBook)
        }
        if (oldBookUrl != newBook.bookUrl) {
            postEvent(EventBus.BOOK_REPLACED, BookReplacedEvent(oldBookUrl, newBook.bookUrl))
        }
        return ChangeBookSourceResult(oldBookUrl, newBook)
    }

    suspend fun batchChangeTo(
        books: List<Book>,
        source: BookSource,
        options: ChangeSourceMigrationOptions,
        onProgress: (current: Int, total: Int, bookName: String) -> Unit,
    ): BatchChangeBookSourceResult {
        var changedCount = 0
        var failedCount = 0
        var skippedCount = 0
        books.forEachIndexed { index, book ->
            onProgress(index + 1, books.size, book.name)
            if (book.isLocal || book.origin == source.bookSourceUrl) {
                skippedCount++
                return@forEachIndexed
            }
            val newBook = WebBook.preciseSearchAwait(source, book.name, book.author)
                .onFailure {
                    AppLog.put("搜索书籍出错\n${it.localizedMessage}", it, true)
                }.getOrNull()
            if (newBook == null) {
                failedCount++
                return@forEachIndexed
            }
            val infoLoaded = kotlin.runCatching {
                if (newBook.tocUrl.isEmpty()) {
                    WebBook.getBookInfoAwait(source, newBook)
                }
            }.onFailure {
                AppLog.put("获取书籍详情出错\n${it.localizedMessage}", it, true)
            }.isSuccess
            if (!infoLoaded) {
                failedCount++
                return@forEachIndexed
            }
            val chapters = WebBook.getChapterListAwait(source, newBook)
                .onFailure {
                    AppLog.put("获取目录出错\n${it.localizedMessage}", it, true)
                }.getOrNull()
            if (chapters == null) {
                failedCount++
            } else {
                changeTo(book, newBook, chapters, options)
                changedCount++
            }
        }
        return BatchChangeBookSourceResult(changedCount, failedCount, skippedCount)
    }

    suspend fun prepareBatchChange(
        books: List<Book>,
        sources: List<BookSource>,
        concurrency: Int,
        onProgress: (current: Int, total: Int, bookName: String) -> Unit,
    ): List<BatchChangeSourcePreviewItem> {
        val progress = AtomicInteger(0)
        return books.withIndex().asFlow()
            .mapAsync(concurrency.coerceAtLeast(1)) { indexedBook ->
                val book = indexedBook.value
                onProgress(progress.incrementAndGet(), books.size, book.name)
                val previewItem = if (book.isLocal) {
                    BatchChangeSourcePreviewItem(
                        oldBook = book,
                        status = BatchChangeSourcePreviewStatus.Skipped
                    )
                } else {
                    val candidates = arrayListOf<BatchChangeSourceCandidate>()
                    sources.filterNot { it.bookSourceUrl == book.origin }.forEach { source ->
                        findBookInSource(book, source)?.let { candidate ->
                            candidates.add(candidate)
                        }
                    }
                    if (candidates.isEmpty()) {
                        BatchChangeSourcePreviewItem(oldBook = book)
                    } else {
                        BatchChangeSourcePreviewItem(
                            oldBook = book,
                            candidates = candidates,
                            status = BatchChangeSourcePreviewStatus.Matched
                        )
                    }
                }
                indexedBook.index to previewItem
            }
            .toList()
            .sortedBy { it.first }
            .map { it.second }
    }

    private suspend fun findBookInSource(
        oldBook: Book,
        source: BookSource,
    ): BatchChangeSourceCandidate? {
        val newBook = WebBook.preciseSearchAwait(source, oldBook.name, oldBook.author)
            .onFailure {
                AppLog.put("搜索书籍出错\n${it.localizedMessage}", it, true)
            }.getOrNull() ?: return null
        val chapters = loadCandidateChapters(source, newBook) ?: return null
        return BatchChangeSourceCandidate(
            source = source,
            book = newBook,
            chapterCount = chapters.size,
        )
    }

    suspend fun loadCandidateChapters(
        source: BookSource,
        book: Book,
    ): List<BookChapter>? {
        val infoLoaded = kotlin.runCatching {
            if (book.tocUrl.isEmpty()) {
                WebBook.getBookInfoAwait(source, book)
            }
        }.onFailure {
            AppLog.put("获取书籍详情出错\n${it.localizedMessage}", it, true)
        }.isSuccess
        if (!infoLoaded) return null
        val chapters = WebBook.getChapterListAwait(source, book)
            .onFailure {
                AppLog.put("获取目录出错\n${it.localizedMessage}", it, true)
            }.getOrNull() ?: return null
        book.totalChapterNum = chapters.size
        return chapters
    }

    private suspend fun Book.applyMigrationTo(
        newBook: Book,
        chapters: List<BookChapter>,
        options: ChangeSourceMigrationOptions,
        defaultReplaceEnabled: Boolean,
        chineseConverterType: Int,
        keepOfficialMeta: Boolean,
    ) {
        newBook.totalChapterNum = chapters.size
        if (options.migrateReadingProgress && chapters.isNotEmpty()) {
            newBook.durChapterIndex = BookHelp
                .getDurChapter(durChapterIndex, durChapterTitle, chapters, totalChapterNum)
                .coerceIn(0, chapters.lastIndex)
            newBook.durChapterTitle = chapters[newBook.durChapterIndex].getDisplayTitle(
                ContentProcessor.get(newBook.name, newBook.origin).getTitleReplaceRules(),
                getUseReplaceRuleToc(defaultReplaceEnabled),
                chineseConverterType = chineseConverterType,
            )
            newBook.durChapterPos = durChapterPos
            newBook.durChapterTime = durChapterTime
        } else {
            newBook.durChapterIndex = 0
            newBook.durChapterTitle = chapters.firstOrNull()?.getDisplayTitle(
                ContentProcessor.get(newBook.name, newBook.origin).getTitleReplaceRules(),
                getUseReplaceRuleToc(defaultReplaceEnabled),
                chineseConverterType = chineseConverterType,
            )
            newBook.durChapterPos = 0
            newBook.durChapterTime = System.currentTimeMillis()
        }
        if (options.migrateGroup) {
            newBook.group = group
            newBook.order = order
        }
        if (options.migrateCover) {
            newBook.customCoverUrl = customCoverUrl
        }
        if (options.migrateCategory) {
            newBook.customTag = customTag
            // kind 不迁移：新书源应提供自己的 kind，避免旧标签残留
            // newBook.kind = kind
        }
        if (options.migrateRemark) {
            newBook.customIntro = customIntro
            newBook.remark = remark
        }
        newBook.canUpdate = canUpdate
        // 换源到本地文件时不能套用旧书的固定类型，否则会盖掉 local 类型位，isLocal 失效读不出正文
        if (config.fixedType && !newBook.isLocal) {
            newBook.type = type
        }
        if (options.migrateReadConfig) {
            newBook.readConfig = readConfig
        }
        if (newBook.wordCount.isNullOrBlank()) {
            newBook.wordCount = wordCount
        }
        // 保持正版基础信息：切换到非正版书源时，保留旧书基础元信息不被新源覆盖
        if (keepOfficialMeta) {
            val newSource = database.bookSourceDao.getBookSource(newBook.origin)
            if (!TagManager.isOfficialSource(newSource)) {
                newBook.name = name
                newBook.author = author
                newBook.customCoverUrl = customCoverUrl
                newBook.coverUrl = coverUrl
                newBook.customIntro = customIntro
                newBook.intro = intro
                newBook.kind = kind  // 正版→非正版时保留 kind
                newBook.wordCount = wordCount  // 正版→非正版时保留字数
            }
        }
        // 换源到本地文件后，刷新会走 LocalBook.upBookInfo 重新解析文件，把 intro/coverUrl 覆盖成文件自带的。
        // 把迁移过来的简介和封面固化到 custom 字段：显示走 getDisplayIntro()/getDisplayCover()，重新解析盖不掉。
        if (newBook.isLocal) {
            if (newBook.customIntro.isNullOrBlank() && !newBook.intro.isNullOrBlank()) {
                newBook.customIntro = newBook.intro
            }
            if (newBook.customCoverUrl.isNullOrBlank() && !newBook.coverUrl.isNullOrBlank()) {
                newBook.customCoverUrl = newBook.coverUrl
            }
        }
    }
}
