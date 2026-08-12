package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.ShareCardTemplate
import kotlinx.coroutines.flow.Flow

@Dao
interface ShareCardTemplateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(template: ShareCardTemplate): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vararg templates: ShareCardTemplate): List<Long>

    @Query("DELETE FROM shareCardTemplates WHERE isBuiltin = 0")
    suspend fun deleteUserTemplates()

    @Update
    suspend fun update(template: ShareCardTemplate)

    @Delete
    suspend fun delete(template: ShareCardTemplate)

    @Query("SELECT * FROM shareCardTemplates ORDER BY updateTime DESC")
    fun flowAll(): Flow<List<ShareCardTemplate>>

    @Query("SELECT * FROM shareCardTemplates ORDER BY updateTime DESC")
    suspend fun getAll(): List<ShareCardTemplate>

    @Query("SELECT * FROM shareCardTemplates WHERE id = :id")
    suspend fun getById(id: Long): ShareCardTemplate?

    @Query("SELECT DISTINCT groupName FROM shareCardTemplates WHERE TRIM(groupName) <> '' ORDER BY groupName ASC")
    suspend fun getDistinctGroupNames(): List<String>

    @Query("SELECT * FROM shareCardTemplates WHERE groupName = :groupName ORDER BY updateTime DESC")
    suspend fun getByGroupName(groupName: String): List<ShareCardTemplate>

    @Query("SELECT * FROM shareCardTemplates WHERE isBuiltin = 1 AND groupName = :groupName ORDER BY id ASC")
    suspend fun getBuiltinsByGroupName(groupName: String): List<ShareCardTemplate>

    @Query("DELETE FROM shareCardTemplates WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM shareCardTemplates WHERE groupName = :groupName")
    suspend fun deleteByGroupName(groupName: String)

    @Query("UPDATE shareCardTemplates SET groupName = :newName WHERE groupName = :oldName")
    suspend fun updateGroupName(oldName: String, newName: String)

    @Query("SELECT COUNT(*) FROM shareCardTemplates WHERE groupName = :groupName")
    suspend fun countByGroupName(groupName: String): Int
}
