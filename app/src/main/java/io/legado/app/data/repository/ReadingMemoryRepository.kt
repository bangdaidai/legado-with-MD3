package io.legado.app.data.repository

import io.legado.app.data.dao.BookmarkDao
import io.legado.app.data.dao.ReadingMemoryDao
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.entities.readRecord.ReadRecordTimelineDay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 阅读记忆仓库
 *
 * 设计原则（遵循融合铁律：逻辑层整体移植，UI 层按 MD3 重建）：
 * - 时间线直接复用书籍信息页的阅读记录（ReadRecordRepository.getBookTimelineDays）
 * - 书摘使用「有笔记的书签」替代（Bookmark.content 非空）
 */
class ReadingMemoryRepository(
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
}
