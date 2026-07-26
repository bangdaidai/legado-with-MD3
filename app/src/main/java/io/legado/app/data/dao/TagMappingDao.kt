package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.TagMapping
import kotlinx.coroutines.flow.Flow

@Dao
interface TagMappingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mapping: TagMapping): Long

    @Update
    suspend fun update(mapping: TagMapping)

    @Delete
    suspend fun delete(mapping: TagMapping)

    @Query("DELETE FROM tagMappings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM tagMappings WHERE oldTagName = :oldTagName LIMIT 1")
    suspend fun getByOldTagName(oldTagName: String): TagMapping?

    @Query("SELECT * FROM tagMappings WHERE newTagId = :newTagId")
    suspend fun getByNewTagId(newTagId: Long): List<TagMapping>

    @Query("SELECT * FROM tagMappings ORDER BY oldTagName COLLATE NOCASE")
    fun observeAll(): Flow<List<TagMapping>>

    @Query("SELECT * FROM tagMappings")
    suspend fun getAll(): List<TagMapping>
}
