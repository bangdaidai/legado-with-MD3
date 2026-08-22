package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.AuthorProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface AuthorProfileDao {

    /** 全部（Flow，供作者列表与作者详情共享） */
    @Query("SELECT * FROM authorProfiles")
    fun getAll(): Flow<List<AuthorProfile>>

    /** 全量查询（供备份使用，非 Flow） */
    @Query("SELECT * FROM authorProfiles")
    suspend fun getAllSync(): List<AuthorProfile>

    /** 插入或替换 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: AuthorProfile)

    /** 批量插入或替换（供备份恢复使用） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(profiles: List<AuthorProfile>)

    /** 批量插入（冲突时忽略，不覆盖已有记录） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIfAbsent(profiles: List<AuthorProfile>)

    /** 按作者名删除 */
    @Query("DELETE FROM authorProfiles WHERE name = :name")
    suspend fun deleteByName(name: String)
}
