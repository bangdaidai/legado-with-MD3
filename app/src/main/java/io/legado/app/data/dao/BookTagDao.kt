package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.BookTag
import kotlinx.coroutines.flow.Flow

@Dao
interface BookTagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: BookTag): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<BookTag>): List<Long>

    @Update
    suspend fun update(tag: BookTag)

    @Delete
    suspend fun delete(tag: BookTag)

    @Query("DELETE FROM bookTags WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM bookTags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): BookTag?

    @Query("SELECT * FROM bookTags WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BookTag?

    @Query("SELECT * FROM bookTags WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<BookTag>

    @Query("SELECT * FROM bookTags WHERE name IN (:names)")
    suspend fun getByNames(names: List<String>): List<BookTag>

    @Query("SELECT * FROM bookTags")
    suspend fun getAllSync(): List<BookTag>

    @Query("SELECT * FROM bookTags ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<BookTag>>
}
