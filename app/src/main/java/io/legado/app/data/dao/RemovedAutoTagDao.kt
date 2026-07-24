package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.RemovedAutoTag
import kotlinx.coroutines.flow.Flow

@Dao
interface RemovedAutoTagDao {

    @Query("SELECT * FROM removedAutoTags ORDER BY name ASC")
    fun observeAll(): Flow<List<RemovedAutoTag>>

    @Query("SELECT * FROM removedAutoTags WHERE name = :name AND type = :type LIMIT 1")
    suspend fun getByName(name: String, type: Int = 0): RemovedAutoTag?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: RemovedAutoTag): Long

    @Delete
    suspend fun delete(tag: RemovedAutoTag)

    @Query("DELETE FROM removedAutoTags WHERE name = :name")
    suspend fun deleteByName(name: String)
}
