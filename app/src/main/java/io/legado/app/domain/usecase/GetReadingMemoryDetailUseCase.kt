package io.legado.app.domain.usecase

import io.legado.app.data.dao.BookDao
import io.legado.app.data.dao.BookKnowledgeDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.repository.ReadingMemoryRepository

/**
 * 单本书的阅读记忆详情。
 * 在架时各字段以 Book / BookCharacterProfile / ReadRecord 等实时源为准；
 * 书架删除后回退到 readingMemory 快照。
 */
data class ReadingMemoryDetail(
    // 书基本信息（Book 实时 / ReadingMemory 快照）
    val bookUrl: String,
    val bookName: String,
    val bookAuthor: String,
    val coverUrl: String?,
    val intro: String?,
    val userModifiedIntro: Boolean,
    val kind: String?,
    val wordCount: String?,
    val type: Int,
    val progress: Float,
    val totalChapterNum: Int,
    val durChapterIndex: Int,
    val durChapterPos: Int,

    // 用户编辑字段（仅 ReadingMemory）
    val rating: Float,
    val review: String?,
    val abandoned: Boolean,

    // 时间
    val firstReadTime: Long,
    val finishReadTime: Long,
    val lastReadTime: Long,
    val createTime: Long,
    val updateTime: Long,

    // ReadingMemory 快照专属（仅删除后可见）
    val protagonistsJson: String?,
    val excerptsJson: String?,
    val annotationCount: Int,

    // 主角列表（BookKnowledgeDao 实时 / 快照解析）
    val protagonists: List<BookCharacterProfile>,

    // 阅读统计（ReadRecord 实时 / 快照）
    val statTotalReadTime: Long,
    val statReadingDays: Int,
    val statMaxDayReadTime: Long,
    val statMaxDayReadDate: String?,
    val statTotalWords: Long,

    // 来源标记
    val isStillOnShelf: Boolean,
)

class GetReadingMemoryDetailUseCase(
    private val readingMemoryRepository: ReadingMemoryRepository,
    private val bookDao: BookDao,
    private val bookKnowledgeDao: BookKnowledgeDao,
) {

    /**
     * 获取指定 bookUrl 的阅读记忆详情。
     *
     * - 若书籍仍在书架（Book 表有该记录）：实时从 Book / BookCharacterProfile / ReadRecord 获取数据，
     *   同时补充 ReadingMemory 中的用户编辑字段（评分/书评/弃文标记/简介）。
     * - 若书籍已从书架删除：回退到 readingMemory 快照。
     */
    suspend fun execute(bookUrl: String): ReadingMemoryDetail {
        val book = bookDao.getBook(bookUrl)
        val memory = readingMemoryRepository.getByBookUrl(bookUrl)

        return if (book != null) {
            buildFromBook(book, memory)
        } else {
            buildFromMemory(memory)
        }
    }

    private suspend fun buildFromBook(book: Book, memory: ReadingMemory?): ReadingMemoryDetail {
        val protagonists = bookKnowledgeDao.getProtagonists(book.bookUrl)
        val stats = readingMemoryRepository.computeStatistics(book)

        return ReadingMemoryDetail(
            bookUrl = book.bookUrl,
            bookName = book.name,
            bookAuthor = book.author,
            coverUrl = book.getDisplayCover(),
            intro = memory?.intro ?: book.getDisplayIntro(),
            userModifiedIntro = memory?.userModifiedIntro == true,
            kind = book.kind,
            wordCount = book.wordCount,
            type = book.type,
            progress = book.durChapterTitle?.let { 0f } ?: memory?.progress ?: 0f,
            totalChapterNum = book.totalChapterNum,
            durChapterIndex = book.durChapterIndex,
            durChapterPos = book.durChapterPos,
            rating = memory?.rating ?: 0f,
            review = memory?.review,
            abandoned = memory?.abandoned ?: false,
            firstReadTime = memory?.firstReadTime ?: 0L,
            finishReadTime = if (book.lastCheckTime > 0) book.lastCheckTime else (memory?.finishReadTime ?: 0L),
            lastReadTime = book.lastCheckTime.takeIf { it > 0 } ?: (memory?.lastReadTime ?: 0L),
            createTime = memory?.createTime ?: 0L,
            updateTime = memory?.updateTime ?: 0L,
            protagonistsJson = memory?.protagonistsJson,
            excerptsJson = memory?.excerptsJson,
            annotationCount = memory?.annotationCount ?: 0,
            protagonists = protagonists,
            statTotalReadTime = stats.totalReadTime,
            statReadingDays = stats.readingDays,
            statMaxDayReadTime = stats.maxDayReadTime,
            statMaxDayReadDate = stats.maxDayReadDate,
            statTotalWords = stats.totalWords,
            isStillOnShelf = true,
        )
    }

    private fun buildFromMemory(memory: ReadingMemory?): ReadingMemoryDetail {
        if (memory == null) {
            return emptyDetail("")
        }

        // 解析主角 JSON（格式：name1|name2|...）
        val protagonistNames = memory.protagonistsJson
            ?.split("|")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val protagonists = protagonistNames.map { name ->
            BookCharacterProfile(
                id = java.util.UUID.randomUUID().toString(),
                bookUrl = memory.bookUrl,
                name = name,
                isProtagonist = true,
                source = BookCharacterProfile.SOURCE_AI,
            )
        }

        return ReadingMemoryDetail(
            bookUrl = memory.bookUrl,
            bookName = memory.bookName,
            bookAuthor = memory.bookAuthor,
            coverUrl = memory.coverUrl,
            intro = memory.intro,
            userModifiedIntro = memory.userModifiedIntro,
            kind = memory.kind,
            wordCount = memory.wordCount,
            type = memory.type,
            progress = memory.progress,
            totalChapterNum = memory.totalChapterNum,
            durChapterIndex = memory.durChapterIndex,
            durChapterPos = memory.durChapterPos,
            rating = memory.rating,
            review = memory.review,
            abandoned = memory.abandoned,
            firstReadTime = memory.firstReadTime,
            finishReadTime = memory.finishReadTime,
            lastReadTime = memory.lastReadTime,
            createTime = memory.createTime,
            updateTime = memory.updateTime,
            protagonistsJson = memory.protagonistsJson,
            excerptsJson = memory.excerptsJson,
            annotationCount = memory.annotationCount,
            protagonists = protagonists,
            statTotalReadTime = memory.statTotalReadTime,
            statReadingDays = memory.statReadingDays,
            statMaxDayReadTime = memory.statMaxDayReadTime,
            statMaxDayReadDate = memory.statMaxDayReadDate,
            statTotalWords = memory.statTotalWords,
            isStillOnShelf = false,
        )
    }

    private fun emptyDetail(bookUrl: String): ReadingMemoryDetail = ReadingMemoryDetail(
        bookUrl = bookUrl,
        bookName = "",
        bookAuthor = "",
        coverUrl = null,
        intro = null,
        userModifiedIntro = false,
        kind = null,
        wordCount = null,
        type = 0,
        progress = 0f,
        totalChapterNum = 0,
        durChapterIndex = 0,
        durChapterPos = 0,
        rating = 0f,
        review = null,
        abandoned = false,
        firstReadTime = 0L,
        finishReadTime = 0L,
        lastReadTime = 0L,
        createTime = 0L,
        updateTime = 0L,
        protagonistsJson = null,
        excerptsJson = null,
        annotationCount = 0,
        protagonists = emptyList(),
        statTotalReadTime = 0L,
        statReadingDays = 0,
        statMaxDayReadTime = 0L,
        statMaxDayReadDate = null,
        statTotalWords = 0L,
        isStillOnShelf = false,
    )
}
