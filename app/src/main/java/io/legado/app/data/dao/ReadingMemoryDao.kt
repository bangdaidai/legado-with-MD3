package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.ReadingMemory
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingMemoryDao {

    @Query("SELECT * FROM readingMemories WHERE bookUrl = :bookUrl")
    suspend fun getByBookUrl(bookUrl: String): ReadingMemory?

    @Query("SELECT * FROM readingMemories WHERE bookUrl = :bookUrl")
    fun observeByBookUrl(bookUrl: String): Flow<ReadingMemory?>

    @Query("SELECT * FROM readingMemories ORDER BY lastReadTime DESC")
    fun flowAll(): Flow<List<ReadingMemory>>

    @Query("SELECT * FROM readingMemories WHERE bookUrl IN (:bookUrls)")
    suspend fun getByBookUrls(bookUrls: List<String>): List<ReadingMemory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: ReadingMemory)

    @Update
    suspend fun update(memory: ReadingMemory)

    @Query(
        "UPDATE readingMemories SET readingStatus = :status, " +
            "userModifiedReadingStatus = 1, updateTime = :updateTime " +
            "WHERE bookUrl = :bookUrl"
    )
    suspend fun updateStatus(
        bookUrl: String,
        status: Int,
        updateTime: Long = System.currentTimeMillis()
    )

    @Delete
    suspend fun delete(memory: ReadingMemory)

    @Query("DELETE FROM readingMemories WHERE bookUrl = :bookUrl")
    suspend fun deleteByBookUrl(bookUrl: String)
}
