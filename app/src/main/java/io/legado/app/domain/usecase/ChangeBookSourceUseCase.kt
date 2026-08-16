package io.legado.app.domain.usecase

import androidx.room.withTransaction
import io.legado.app.constant.AppLog
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.dao.BookChapterDao
import io.legado.app.data.dao.BookDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
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
            }
        }
        // 换源后重建新书的标签关联，并清理旧 bookUrl 上的孤儿关联，避免标签丢失
        if (oldBookUrl != newBook.bookUrl) {
            database.bookTagRelationDao.deleteByBookUrl(oldBookUrl)
        }
        TagManager.generateTagsFromKind(newBook)
        if (effectiveOptions.migrateChapters) {
            ReadBook.onChapterListUpdated(newBook)
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
                getUseReplaceRule(defaultReplaceEnabled),
                chineseConverterType = chineseConverterType,
            )
            newBook.durChapterPos = durChapterPos
            newBook.durChapterTime = durChapterTime
        } else {
            newBook.durChapterIndex = 0
            newBook.durChapterTitle = chapters.firstOrNull()?.getDisplayTitle(
                ContentProcessor.get(newBook.name, newBook.origin).getTitleReplaceRules(),
                getUseReplaceRule(defaultReplaceEnabled),
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
            newBook.kind = kind
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
