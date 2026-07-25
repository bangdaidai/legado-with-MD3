package io.legado.app.data.repository

import io.legado.app.data.AppDatabase
import io.legado.app.data.dao.BookmarkDao
import io.legado.app.data.dao.ReadingMemoryDao
import io.legado.app.data.entities.BookProtagonist
import io.legado.app.data.entities.BookReview
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagRelation
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.data.entities.readRecord.ReadRecordTimelineDay
import io.legado.app.utils.getCurrentDeviceId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 阅读记忆仓库
 *
 * 设计原则（遵循融合铁律：逻辑层整体移植，UI 层按 MD3 重建）：
 * - 时间线直接复用书籍信息页的阅读记录（ReadRecordRepository.getBookTimelineDays）
 * - 书摘使用「有笔记的书签」替代（Bookmark.content 非空）
 * - 主角 / 书评 / 标签 复用各自实体表，与 readdai 同构
 */
class ReadingMemoryRepository(
    private val appDb: AppDatabase,
    private val readingMemoryDao: ReadingMemoryDao,
    private val readRecordRepository: ReadRecordRepository,
    private val bookmarkDao: BookmarkDao,
) {

    fun observeMemory(bookUrl: String): Flow<ReadingMemory?> =
        readingMemoryDao.observeByBookUrl(bookUrl)

    fun getBookTimelineDays(
        bookName: String,
        bookAuthor: String,
    ): Flow<List<ReadRecordTimelineDay>> =
        readRecordRepository.getBookTimelineDays(bookName, bookAuthor)

    fun getBookReadTime(bookName: String, bookAuthor: String): Flow<Long> =
        readRecordRepository.getBookReadTime(bookName, bookAuthor)

    /** 书摘：取带有笔记的书签（content 非空） */
    fun getExcerpts(bookName: String, bookAuthor: String): Flow<List<Bookmark>> =
        bookmarkDao.flowByBook(bookName, bookAuthor)
            .map { list -> list.filter { it.content.isNotBlank() } }

    /** 保存（插入或覆盖，按主键 bookUrl） */
    suspend fun saveMemory(memory: ReadingMemory) {
        readingMemoryDao.insert(memory)
    }

    // ===== 标签 =====
    fun observeTags(bookUrl: String): Flow<List<BookTag>> =
        appDb.bookTagRelationDao.observeTagsByBook(bookUrl)

    suspend fun addTag(bookUrl: String, tagName: String) {
        val name = tagName.trim()
        if (name.isEmpty()) return
        val existing = appDb.bookTagDao.getByName(name)
        val tagId = existing?.id ?: appDb.bookTagDao.insert(BookTag(name = name, color = 0))
        if (tagId > 0) {
            appDb.bookTagRelationDao.insert(
                BookTagRelation(
                    id = BookTagRelation.generateId(),
                    bookUrl = bookUrl,
                    tagId = tagId,
                ),
            )
        }
    }

    suspend fun removeTag(bookUrl: String, tag: BookTag) {
        appDb.bookTagRelationDao.delete(bookUrl, tag.id)
    }

    // ===== 主角（人设） =====
    fun observeProtagonists(bookUrl: String): Flow<List<BookProtagonist>> =
        appDb.bookProtagonistDao.observeByBook(bookUrl)

    suspend fun addProtagonist(bookUrl: String, name: String, isCustom: Boolean = true) {
        val n = name.trim()
        if (n.isEmpty()) return
        if (appDb.bookProtagonistDao.getByName(bookUrl, n) != null) return
        appDb.bookProtagonistDao.insert(
            BookProtagonist(
                bookUrl = bookUrl,
                name = n,
                isCustom = isCustom,
                updateTime = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun removeProtagonist(id: Long) {
        appDb.bookProtagonistDao.deleteById(id)
    }

    // ===== 书评 =====
    fun observeReviews(bookUrl: String): Flow<List<BookReview>> =
        appDb.bookReviewDao.observeByBook(bookUrl)

    suspend fun addReview(review: BookReview): Long =
        appDb.bookReviewDao.insert(review)

    suspend fun updateReview(review: BookReview) {
        appDb.bookReviewDao.update(review)
    }

    suspend fun deleteReview(id: Long) {
        appDb.bookReviewDao.deleteById(id)
    }

    // ===== 阅读会话（用于阅读数据维度计算，对齐 readdai） =====
    fun observeSessions(bookName: String, bookAuthor: String): Flow<List<ReadRecordSession>> =
        appDb.readRecordDao.getSessionsByBookFlow(getCurrentDeviceId(), bookName, bookAuthor)
}
