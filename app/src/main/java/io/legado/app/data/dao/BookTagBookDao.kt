package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.BookTagBook
import kotlinx.coroutines.flow.Flow

@Dao
interface BookTagBookDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookTagBook: BookTagBook): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<BookTagBook>)

    @Delete
    suspend fun delete(bookTagBook: BookTagBook)

    @Query("DELETE FROM bookTagBooks WHERE tagName = :tagName")
    suspend fun deleteByTagName(tagName: String)

    @Query("DELETE FROM bookTagBooks WHERE bookUrl = :bookUrl")
    suspend fun deleteByBookUrl(bookUrl: String)

    @Query("SELECT * FROM bookTagBooks WHERE tagName = :tagName ORDER BY bookName COLLATE NOCASE")
    fun observeByTagName(tagName: String): Flow<List<BookTagBook>>

    @Query("SELECT tagName, COUNT(*) AS bookCount FROM bookTagBooks GROUP BY tagName")
    suspend fun getTagBookCounts(): List<TagBookCount>

    data class TagBookCount(val tagName: String, val bookCount: Int)
}
