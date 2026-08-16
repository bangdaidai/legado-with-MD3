package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.RemovedAutoTag

@Dao
interface RemovedAutoTagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(removedAutoTag: RemovedAutoTag): Long

    @Query("SELECT * FROM removedAutoTags WHERE tagName = :tagName LIMIT 1")
    suspend fun getByTagName(tagName: String): RemovedAutoTag?

    @Query("SELECT * FROM removedAutoTags")
    suspend fun getAll(): List<RemovedAutoTag>

    /** 恢复用：批量写入 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(removedAutoTags: List<RemovedAutoTag>)

    @Query("DELETE FROM removedAutoTags WHERE tagName = :tagName")
    suspend fun deleteByTagName(tagName: String)

    @Query("DELETE FROM removedAutoTags")
    suspend fun clear()
}
