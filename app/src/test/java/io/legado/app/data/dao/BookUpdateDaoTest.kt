package io.legado.app.data.dao

import androidx.room.Room
import io.legado.app.constant.BookType
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookUpdate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 今日书讯展示查询的 SQL 侧：按天 JOIN books、排除本地/非书架来源、
 * 按发现时间倒序，以及同日记录只前进不回退的合并语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class BookUpdateDaoTest {

    private lateinit var db: AppDatabase

    private val sourceUrl = "http://example.com/source"
    private val bookA = "http://example.com/book/a"
    private val bookB = "http://example.com/book/b"
    private val localBook = "http://example.com/book/local"
    private val notShelfBook = "http://example.com/book/not-shelf"
    private val webDavBook = "http://example.com/book/webdav"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun joinsShelfBooksOrderedByFoundAtDesc() = runBlocking {
        db.bookDao.insert(
            Book(bookUrl = bookA, origin = sourceUrl, name = "甲"),
            Book(bookUrl = bookB, origin = sourceUrl, name = "乙"),
        )
        db.bookUpdateDao.insert(
            BookUpdate(19723L, bookA, sourceUrl, latestChapterTitle = "第2章", foundAt = 1000L,
                totalChapterNum = 102, baselineChapterNum = 100),
            BookUpdate(19723L, bookB, sourceUrl, latestChapterTitle = "第5章", foundAt = 2000L,
                totalChapterNum = 105, baselineChapterNum = 100),
        )

        val rows = db.bookUpdateDao.observeBooksForDay(19723L).first()

        assertEquals(listOf(bookB, bookA), rows.map { it.book.bookUrl })
        assertEquals(105, rows[0].updateTotalChapterNum)
        assertEquals(100, rows[0].updateBaselineChapterNum)
    }

    @Test
    fun excludesLocalNotShelfAndWebDavBooks() = runBlocking {
        db.bookDao.insert(
            Book(bookUrl = bookA, origin = sourceUrl, name = "在线书"),
            Book(bookUrl = localBook, origin = sourceUrl, name = "本地书", type = BookType.local),
            Book(bookUrl = notShelfBook, origin = sourceUrl, name = "非书架", type = BookType.notShelf),
            Book(bookUrl = webDavBook, origin = "${BookType.webDavTag}demo", name = "网盘书"),
        )
        listOf(bookA, localBook, notShelfBook).forEach { url ->
            db.bookUpdateDao.insert(
                BookUpdate(19723L, url, sourceUrl, latestChapterTitle = null, foundAt = 1000L,
                    totalChapterNum = 101, baselineChapterNum = 100)
            )
        }
        // webDav 书的记录行 origin 与书一致，才能确保是 SQL 的 NOT LIKE 把它过滤掉
        db.bookUpdateDao.insert(
            BookUpdate(19723L, webDavBook, "${BookType.webDavTag}demo", latestChapterTitle = null,
                foundAt = 1000L, totalChapterNum = 101, baselineChapterNum = 100)
        )

        val urls = db.bookUpdateDao.observeBooksForDay(19723L).first().map { it.book.bookUrl }

        assertEquals(listOf(bookA), urls)
    }

    @Test
    fun updatesWithoutShelfBookDisappear() = runBlocking {
        // 书已移出书架：记录行还在，但 INNER JOIN 后不再展示
        db.bookUpdateDao.insert(
            BookUpdate(19723L, bookA, sourceUrl, latestChapterTitle = null, foundAt = 1000L,
                totalChapterNum = 101, baselineChapterNum = 100)
        )

        assertTrue(db.bookUpdateDao.observeBooksForDay(19723L).first().isEmpty())
    }

    @Test
    fun recordIfNewerKeepsFirstBaselineAndIgnoresOlderTotals() = runBlocking {
        val epochDay = 19723L

        db.bookUpdateDao.recordIfNewer(
            BookUpdate(epochDay, bookA, sourceUrl, latestChapterTitle = "第2章", foundAt = 1000L,
                totalChapterNum = 102, baselineChapterNum = 100)
        )
        // 同日更高：total 前进，基线保留 100
        db.bookUpdateDao.recordIfNewer(
            BookUpdate(epochDay, bookA, sourceUrl, latestChapterTitle = "第5章", foundAt = 2000L,
                totalChapterNum = 105, baselineChapterNum = 102)
        )
        val first = db.bookUpdateDao.get(epochDay, bookA, sourceUrl)
        assertEquals(105, first?.totalChapterNum)
        assertEquals(100, first?.baselineChapterNum)

        // 同日更低/相等：整条忽略
        db.bookUpdateDao.recordIfNewer(
            BookUpdate(epochDay, bookA, sourceUrl, latestChapterTitle = "第3章", foundAt = 3000L,
                totalChapterNum = 103, baselineChapterNum = 102)
        )
        assertEquals(105, db.bookUpdateDao.get(epochDay, bookA, sourceUrl)?.totalChapterNum)
    }

    @Test
    fun getReturnsNullForOtherDayOrBook() = runBlocking {
        db.bookUpdateDao.insert(
            BookUpdate(19723L, bookA, sourceUrl, latestChapterTitle = null, foundAt = 1000L,
                totalChapterNum = 101, baselineChapterNum = 100)
        )

        assertNull(db.bookUpdateDao.get(19724L, bookA, sourceUrl))
        assertNull(db.bookUpdateDao.get(19723L, bookB, sourceUrl))
        assertNull(db.bookUpdateDao.get(19723L, bookA, "http://example.com/other"))
    }
}
