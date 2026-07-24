package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.ExcludedTag
import kotlinx.coroutines.flow.Flow

@Dao
interface ExcludedTagDao {

    @Query("SELECT * FROM excludedTags ORDER BY name ASC")
    fun observeAll(): Flow<List<ExcludedTag>>

    @Query("SELECT * FROM excludedTags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): ExcludedTag?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: ExcludedTag): Long

    @Delete
    suspend fun delete(tag: ExcludedTag)

    @Query("DELETE FROM excludedTags WHERE name = :name")
    suspend fun deleteByName(name: String)
}
