package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookUpdate
import io.legado.app.data.entities.mergeBookUpdateIfNewer
import kotlinx.coroutines.flow.Flow

/** 目录刷新后按天 JOIN 出的展示行；bookUpdates 侧列用 update* 别名避免与 Book 列名冲突 */
data class BookUpdateWithBook(
    @Embedded val book: Book,
    val updateFoundAt: Long,
    val updateTotalChapterNum: Int,
    val updateBaselineChapterNum: Int?,
)

@Dao
interface BookUpdateDao {

    @Query(
        "SELECT * FROM bookUpdates WHERE epochDay = :epochDay AND bookUrl = :bookUrl AND origin = :origin"
    )
    suspend fun get(epochDay: Long, bookUrl: String, origin: String): BookUpdate?

    @Query(
        """
        SELECT books.*, bookUpdates.foundAt AS updateFoundAt,
               bookUpdates.totalChapterNum AS updateTotalChapterNum,
               bookUpdates.baselineChapterNum AS updateBaselineChapterNum
        FROM bookUpdates INNER JOIN books
            ON books.bookUrl = bookUpdates.bookUrl AND books.origin = bookUpdates.origin
        WHERE bookUpdates.epochDay = :epochDay
            AND books.type & ${BookType.local} = 0
            AND books.type & ${BookType.notShelf} = 0
            AND books.origin != '${BookType.localTag}'
            AND books.origin NOT LIKE '${BookType.webDavTag}%'
        ORDER BY bookUpdates.foundAt DESC, books.bookUrl ASC
        """
    )
    fun observeBooksForDay(epochDay: Long): Flow<List<BookUpdateWithBook>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: BookUpdate)

    /** 重复刷新不追加章数：同日已有记录时仅在 total 更大时前进，基线保留当日首值 */
    @Transaction
    suspend fun recordIfNewer(record: BookUpdate) {
        val previous = get(record.epochDay, record.bookUrl, record.origin)
        mergeBookUpdateIfNewer(previous, record)?.let(::insert)
    }
}
