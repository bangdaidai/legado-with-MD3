package io.legado.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import io.legado.app.data.AppDatabase
import io.legado.app.data.dao.BookDao
import io.legado.app.data.dao.BookKnowledgeDao
import io.legado.app.data.dao.ReadRecordDao
import io.legado.app.data.dao.ReadingMemoryDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.help.book.ProtagonistExtractor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import io.legado.app.constant.AppLog

class ReadingMemoryRepository(
    private val dao: ReadingMemoryDao,
    private val bookDao: BookDao,
    private val bookKnowledgeDao: BookKnowledgeDao,
    private val readRecordDao: ReadRecordDao,
    private val database: AppDatabase,
) {

    companion object {
        private const val DEVICE_ID = ""
    }

    // region 基础查询

    fun observeAll(): Flow<List<ReadingMemory>> = dao.getAll()

    fun observeByBookUrl(bookUrl: String): Flow<ReadingMemory?> =
        dao.getByBookUrl(bookUrl).onEach {
            AppLog.put("[阅读记忆] observeByBookUrl bookUrl=$bookUrl result=${it?.bookName ?: "null(无记忆行)"}")
        }

    suspend fun getByBookUrl(bookUrl: String): ReadingMemory? = dao.getByBookUrlSync(bookUrl)

    // endregion

    // region 确保记忆存在 / 同步

    /**
     * 确保 bookUrl 在 readingMemory 表中有一行。
     * 若不存在则插入最小 stub（bookUrl 为主键），若已存在则跳过。
     */
    suspend fun ensureMemory(bookUrl: String): ReadingMemory {
        val existing = dao.getByBookUrlSync(bookUrl)
        if (existing != null) return existing

        val stub = ReadingMemory.defaultStub(bookUrl)
        try {
            dao.insert(stub)
        } catch (_: SQLiteConstraintException) {
            // 并发插入时忽略
        }
        return dao.getByBookUrlSync(bookUrl) ?: stub
    }

    /**
     * 从 Book 实体同步基础信息到阅读记忆（不覆盖用户编辑字段）。
     * 通常在书籍信息刷新（如封面/简介/书名变化）时调用。
     */
    suspend fun syncFromBook(book: Book) {
        val memory = dao.getByBookUrlSync(book.bookUrl) ?: return
        val now = System.currentTimeMillis()
        dao.syncFromBook(
            bookUrl = book.bookUrl,
            bookName = book.name,
            bookAuthor = book.author,
            coverUrl = book.coverUrl,
            intro = book.intro,
            kind = book.kind,
            wordCount = book.wordCount,
            type = book.type,
            progress = memory.progress,
            totalChapterNum = book.totalChapterNum,
            durChapterIndex = book.durChapterIndex,
            durChapterPos = book.durChapterPos,
            lastReadTime = book.lastCheckTime.takeIf { it > 0 } ?: memory.lastReadTime,
            updateTime = now,
        )
    }

    // endregion

    // region 用户可编辑字段

    suspend fun markAbandoned(bookUrl: String) {
        dao.markAbandoned(bookUrl)
    }

    suspend fun unmarkAbandoned(bookUrl: String) {
        dao.unmarkAbandoned(bookUrl)
    }

    suspend fun updateRating(bookUrl: String, rating: Float) {
        dao.updateRating(bookUrl, rating)
    }

    suspend fun updateReview(bookUrl: String, review: String?) {
        dao.updateReview(bookUrl, review)
    }

    suspend fun updateProgress(
        bookUrl: String,
        durChapterIndex: Int,
        durChapterPos: Int,
        progress: Float,
        totalChapterNum: Int,
    ) {
        dao.updateProgress(
            bookUrl = bookUrl,
            progress = progress,
            durChapterIndex = durChapterIndex,
            durChapterPos = durChapterPos,
            lastReadTime = System.currentTimeMillis(),
            totalChapterNum = totalChapterNum,
            updateTime = System.currentTimeMillis(),
        )
    }

    // endregion

    // region 删除前快照

    /**
     * 书架删除书籍前调用：将当前书籍信息快照到 readingMemory。
     * 快照后记忆不会被删除，保留阅读历史。
     */
    suspend fun snapshotOnDelete(book: Book) {
        val memory = dao.getByBookUrlSync(book.bookUrl) ?: ensureMemory(book.bookUrl)

        // 提取主角信息做 JSON 快照
        val protagonists = bookKnowledgeDao.getProtagonists(book.bookUrl)
        val protagonistsJson = if (protagonists.isNotEmpty()) {
            protagonists.joinToString("|") { it.name }
        } else {
            memory.protagonistsJson
        }

        // 计算阅读统计
        val stats = computeStatistics(book)

        val now = System.currentTimeMillis()
        dao.snapshotOnDelete(
            bookUrl = book.bookUrl,
            protagonistsJson = protagonistsJson,
            excerptsJson = memory.excerptsJson,
            statTotalReadTime = stats.totalReadTime,
            statReadingDays = stats.readingDays,
            statMaxDayReadTime = stats.maxDayReadTime,
            statMaxDayReadDate = stats.maxDayReadDate,
            statTotalWords = stats.totalWords,
            annotationCount = memory.annotationCount,
            progress = book.durChapterTitle?.let { 0f } ?: memory.progress,
            totalChapterNum = book.totalChapterNum.takeIf { it > 0 } ?: memory.totalChapterNum,
            durChapterIndex = book.durChapterIndex,
            durChapterPos = book.durChapterPos,
            lastReadTime = book.lastCheckTime.takeIf { it > 0 } ?: memory.lastReadTime,
            updateTime = now,
        )
    }

    // endregion

    // region 批量操作

    suspend fun ensureAllMemories() {
        val books = bookDao.all
        books.forEach { book ->
            ensureMemory(book.bookUrl)
        }
    }

    suspend fun syncFromBook(bookUrl: String) {
        val book = bookDao.getBook(bookUrl) ?: return
        syncFromBook(book)
    }

    suspend fun computeStatistics(bookUrl: String): ReadingStatistics {
        val book = bookDao.getBook(bookUrl)
        AppLog.put("[阅读记忆] computeStatistics bookUrl=$bookUrl book=${book?.name ?: "null(书籍不在库)"}")
        if (book == null) return ReadingStatistics(0L, 0, 0L, null, 0L)
        return computeStatistics(book)
    }

    suspend fun updateIntro(bookUrl: String, intro: String?) {
        dao.updateIntro(bookUrl, intro)
    }

    suspend fun snapshotOnDelete(bookUrl: String) {
        val book = bookDao.getBook(bookUrl) ?: return
        snapshotOnDelete(book)
    }

    // endregion

    // region 统计

    /**
     * 计算指定书籍的阅读统计信息。
     */
    suspend fun computeStatistics(book: Book): ReadingStatistics {
        val readRecord = readRecordDao.getReadRecord(DEVICE_ID, book.name, book.author)
        if (readRecord == null) {
            return ReadingStatistics(
                totalReadTime = 0L,
                readingDays = 0,
                maxDayReadTime = 0L,
                maxDayReadDate = null,
                totalWords = 0L,
            )
        }

        val totalReadTime = readRecord.readTime
        val details = readRecordDao.getDetailsByBook(DEVICE_ID, book.name, book.author)

        val readingDays = details.size
        val maxDayDetail = details.maxByOrNull { it.readTime }
        val maxDayReadTime = maxDayDetail?.readTime ?: 0L
        val maxDayReadDate = maxDayDetail?.date
        val totalWords = details.sumOf { it.readWords }

        return ReadingStatistics(
            totalReadTime = totalReadTime,
            readingDays = readingDays,
            maxDayReadTime = maxDayReadTime,
            maxDayReadDate = maxDayReadDate,
            totalWords = totalWords,
        )
    }

    // endregion

    // region 主角提取

    /**
     * 从书籍简介中提取主角名并标记为主角。
     * 提取成功则将主角名写入 book_character_profiles 并设置 isProtagonist = true。
     * 返回提取到的主角名列表。
     */
    suspend fun extractProtagonists(bookUrl: String, intro: String): List<String> {
        val names = ProtagonistExtractor.extract(intro)
        if (names.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        names.forEach { name ->
            val existing = bookKnowledgeDao.getProtagonistByName(bookUrl, name)
            if (existing == null) {
                // 创建新的角色条目并标记为主角
                val profile = BookCharacterProfile(
                    id = UUID.randomUUID().toString(),
                    bookUrl = bookUrl,
                    name = name,
                    source = BookCharacterProfile.SOURCE_AI,
                    isProtagonist = true,
                    createdAt = now,
                    updatedAt = now,
                )
                bookKnowledgeDao.upsertCharacterProfile(profile)
            } else if (!existing.isProtagonist) {
                // 已有角色但未标记为主角，更新标记
                bookKnowledgeDao.setProtagonist(bookUrl, name, true, now)
            }
        }
        return names
    }

    // endregion

    // region 删除操作

    /**
     * 删除指定书籍的阅读记忆。
     */
    suspend fun deleteMemory(bookUrl: String) {
        dao.deleteByBookUrl(bookUrl)
    }

    // endregion
}

/**
 * 书籍阅读统计数据
 */
data class ReadingStatistics(
    val totalReadTime: Long,
    val readingDays: Int,
    val maxDayReadTime: Long,
    val maxDayReadDate: String?,
    val totalWords: Long,
)
