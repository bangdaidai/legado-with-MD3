package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.BookTagGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface BookTagGroupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(group: BookTagGroup): Long

    @Update
    suspend fun update(group: BookTagGroup)

    @Delete
    suspend fun delete(group: BookTagGroup)

    @Query("DELETE FROM bookTagGroups WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM bookTagGroups WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): BookTagGroup?

    @Query("SELECT * FROM bookTagGroups WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): BookTagGroup?

    @Query("SELECT * FROM bookTagGroups ORDER BY sortOrder ASC, name COLLATE NOCASE")
    suspend fun getAllSorted(): List<BookTagGroup>

    @Query("SELECT * FROM bookTagGroups ORDER BY sortOrder ASC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<BookTagGroup>>

    @Query("SELECT COALESCE(MAX(sortOrder), 0) FROM bookTagGroups")
    suspend fun getMaxSortOrder(): Int

    @Query("SELECT COUNT(*) FROM bookTags WHERE groupId = :groupId")
    suspend fun countTagsByGroup(groupId: Long): Int
}
