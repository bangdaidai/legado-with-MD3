package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.ExcludedTag
import kotlinx.coroutines.flow.Flow

@Dao
interface ExcludedTagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(excludedTag: ExcludedTag): Long

    @Update
    suspend fun update(excludedTag: ExcludedTag)

    @Delete
    suspend fun delete(excludedTag: ExcludedTag)

    @Query("DELETE FROM excludedTags WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM excludedTags WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ExcludedTag?

    @Query("SELECT * FROM excludedTags ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ExcludedTag>>

    @Query("SELECT * FROM excludedTags")
    suspend fun getAllSync(): List<ExcludedTag>

    @Query("SELECT * FROM excludedTags WHERE isRegex = 1")
    suspend fun getRegexTags(): List<ExcludedTag>

    @Query("SELECT * FROM excludedTags WHERE isRegex = 0")
    suspend fun getKeywordTags(): List<ExcludedTag>
}
