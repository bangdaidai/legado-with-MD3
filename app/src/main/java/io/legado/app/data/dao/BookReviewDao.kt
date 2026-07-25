package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.BookReview
import kotlinx.coroutines.flow.Flow

@Dao
interface BookReviewDao {

    @Query("SELECT * FROM bookReviews WHERE bookUrl = :bookUrl ORDER BY updateTime DESC")
    fun observeByBook(bookUrl: String): Flow<List<BookReview>>

    @Query("SELECT * FROM bookReviews WHERE bookUrl = :bookUrl ORDER BY updateTime DESC")
    suspend fun getByBook(bookUrl: String): List<BookReview>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(review: BookReview): Long

    @Update
    suspend fun update(review: BookReview)

    @Query("DELETE FROM bookReviews WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM bookReviews WHERE bookUrl = :bookUrl")
    suspend fun deleteByBook(bookUrl: String)
}
