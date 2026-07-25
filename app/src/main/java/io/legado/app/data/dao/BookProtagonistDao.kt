package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.BookProtagonist
import kotlinx.coroutines.flow.Flow

@Dao
interface BookProtagonistDao {

    @Query("SELECT * FROM bookProtagonists WHERE bookUrl = :bookUrl ORDER BY createTime ASC")
    fun observeByBook(bookUrl: String): Flow<List<BookProtagonist>>

    @Query("SELECT * FROM bookProtagonists WHERE bookUrl = :bookUrl")
    suspend fun getByBook(bookUrl: String): List<BookProtagonist>

    @Query("SELECT * FROM bookProtagonists WHERE bookUrl = :bookUrl AND name = :name LIMIT 1")
    suspend fun getByName(bookUrl: String, name: String): BookProtagonist?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(protagonist: BookProtagonist): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(list: List<BookProtagonist>)

    @Query("DELETE FROM bookProtagonists WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM bookProtagonists WHERE bookUrl = :bookUrl")
    suspend fun deleteByBook(bookUrl: String)
}
