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

    @Query("SELECT * FROM bookTags ORDER BY name ASC")
    fun observeAll(): Flow<List<BookTag>>

    @Query("SELECT * FROM bookTags ORDER BY name ASC")
    suspend fun getAll(): List<BookTag>

    @Query("SELECT * FROM bookTags WHERE id = :id")
    suspend fun getTag(id: Long): BookTag?

    @Query("SELECT * FROM bookTags WHERE id = :id")
    suspend fun get(id: Long): BookTag?

    @Query("SELECT * FROM bookTags WHERE name = :name")
    suspend fun getTagByName(name: String): BookTag?

    @Query("SELECT * FROM bookTags WHERE name LIKE '%' || :keyword || '%' ORDER BY name ASC")
    suspend fun searchByKeyword(keyword: String): List<BookTag>

    @Query("SELECT * FROM bookTags WHERE id IN (:ids)")
    suspend fun getTagsByIds(ids: List<Long>): List<BookTag>

    @Query("SELECT * FROM bookTags WHERE name IN (:names)")
    suspend fun getTagsByNames(names: List<String>): List<BookTag>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: BookTag): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<BookTag>): List<Long>

    @Update
    suspend fun update(tag: BookTag)

    @Delete
    suspend fun delete(tag: BookTag)

    @Query("DELETE FROM bookTags WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM bookTags")
    suspend fun deleteAll()

    @Query("DELETE FROM bookTags WHERE name = :name")
    suspend fun deleteByName(name: String)

    /**
     * 根据分组ID更新标签的分组ID
     */
    @Query("UPDATE bookTags SET groupId = :newGroupId WHERE groupId = :oldGroupId")
    suspend fun updateGroupIdByGroupId(oldGroupId: Long, newGroupId: Long)
}
