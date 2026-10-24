package io.legado.app.data.repository

import io.legado.app.constant.AppLog
import io.legado.app.data.dao.BookUpdateDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookUpdate
import io.legado.app.data.entities.bookUpdateChapterCount
import io.legado.app.data.entities.bookUpdateEpochDay
import io.legado.app.domain.model.HomeBookNewsBook
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 今日书讯：目录刷新后按天记录确认到的目录增长，并供首页卡片按天观察。
 * 记录失败只写日志，绝不影响刷新主流程的结果。
 */
class BookUpdateRepository(
    private val bookUpdateDao: BookUpdateDao,
) {

    /**
     * 在目录刷新提交后调用：oldBook 是本次刷新前的快照，两者章数差即当日确认到的增长。
     * 基线未知（首次入库即有目录等）不记录，避免把首抓当成更新。
     */
    suspend fun recordDiscovery(oldBook: Book, book: Book) {
        if (!isRecordable(oldBook, book)) return
        try {
            withContext(Dispatchers.IO) {
                bookUpdateDao.recordIfNewer(
                    BookUpdate(
                        epochDay = bookUpdateEpochDay(System.currentTimeMillis()),
                        bookUrl = book.bookUrl,
                        origin = book.origin,
                        latestChapterTitle = book.latestChapterTitle,
                        foundAt = System.currentTimeMillis(),
                        totalChapterNum = book.totalChapterNum,
                        baselineChapterNum = oldBook.totalChapterNum,
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.put("记录今日书讯失败", error)
        }
    }

    fun observeBooksForDay(epochDay: Long): Flow<List<HomeBookNewsBook>> =
        bookUpdateDao.observeBooksForDay(epochDay).map { rows ->
            rows.map { row ->
                HomeBookNewsBook(
                    bookUrl = row.book.bookUrl,
                    name = row.book.name,
                    author = row.book.author,
                    origin = row.book.origin,
                    coverPath = row.book.getDisplayCover().takeIf { it.isNotEmpty() },
                    updatedChapterCount = bookUpdateChapterCount(
                        row.updateBaselineChapterNum,
                        row.updateTotalChapterNum,
                    ),
                )
            }
        }

    private fun isRecordable(oldBook: Book, book: Book): Boolean =
        book.bookUrl.isNotBlank() && book.origin.isNotBlank() &&
            !book.isLocal && !book.isNotShelf &&
            oldBook.totalChapterNum > 0 &&
            book.totalChapterNum > oldBook.totalChapterNum
}
