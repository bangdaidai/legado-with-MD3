package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.BookTagGroup
import kotlinx.coroutines.flow.Flow

/**
 * 标签分组数据库操作接口（F1 标签系统）
 */
@Dao
interface BookTagGroupDao {

    @Query("SELECT * FROM bookTagGroups ORDER BY sortOrder ASC")
    fun observeAll(): Flow<List<BookTagGroup>>

    @Query("SELECT * FROM bookTagGroups")
    suspend fun getAll(): List<BookTagGroup>

    @Query("SELECT * FROM bookTagGroups ORDER BY sortOrder ASC")
    suspend fun getAllSorted(): List<BookTagGroup>

    @Query("SELECT * FROM bookTagGroups WHERE id = :id")
    suspend fun getById(id: Long): BookTagGroup?

    @Query("SELECT * FROM bookTagGroups WHERE name = :name")
    suspend fun getByName(name: String): BookTagGroup?

    @Query("SELECT MAX(sortOrder) FROM bookTagGroups")
    suspend fun getMaxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: BookTagGroup): Long

    @Update
    suspend fun update(group: BookTagGroup)

    @Query("UPDATE bookTagGroups SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Delete
    suspend fun delete(group: BookTagGroup)

    @Query("DELETE FROM bookTagGroups WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM bookTagGroups WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("DELETE FROM bookTagGroups")
    suspend fun clear()
}
