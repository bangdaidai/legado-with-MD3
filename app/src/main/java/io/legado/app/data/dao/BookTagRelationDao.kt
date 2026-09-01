package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.BookTagRelation
import kotlinx.coroutines.flow.Flow

@Dao
interface BookTagRelationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(relation: BookTagRelation): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(relations: List<BookTagRelation>)

    @Delete
    suspend fun delete(relation: BookTagRelation)

    @Query("DELETE FROM bookTagRelations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM bookTagRelations WHERE bookUrl = :bookUrl AND tagId = :tagId")
    suspend fun deleteByBookUrlAndTag(bookUrl: String, tagId: Long)

    @Query("DELETE FROM bookTagRelations WHERE tagId = :tagId")
    suspend fun deleteByTagId(tagId: Long)

    @Query("SELECT * FROM bookTagRelations WHERE bookUrl = :bookUrl AND tagId = :tagId LIMIT 1")
    suspend fun getRelation(bookUrl: String, tagId: Long): BookTagRelation?

    @Query("DELETE FROM bookTagRelations WHERE bookUrl = :bookUrl")
    suspend fun deleteByBookUrl(bookUrl: String)

    @Query("SELECT * FROM bookTagRelations WHERE bookUrl = :bookUrl")
    suspend fun getByBookUrl(bookUrl: String): List<BookTagRelation>

    @Query("SELECT * FROM bookTagRelations WHERE tagId = :tagId")
    suspend fun getByTagId(tagId: Long): List<BookTagRelation>

    @Query("SELECT DISTINCT tagId FROM bookTagRelations WHERE bookUrl = :bookUrl")
    suspend fun getTagIdsByBookUrl(bookUrl: String): List<Long>

    @Query("SELECT COUNT(DISTINCT r.bookUrl) FROM bookTagRelations r INNER JOIN books b ON r.bookUrl = b.bookUrl WHERE r.tagId = :tagId")
    suspend fun countBooksByTagId(tagId: Long): Int

    @Query("SELECT r.tagId, COUNT(DISTINCT r.bookUrl) AS cnt FROM bookTagRelations r INNER JOIN books b ON r.bookUrl = b.bookUrl GROUP BY r.tagId")
    suspend fun countAllByTag(): List<TagCount>

    @Query("SELECT * FROM bookTagRelations")
    suspend fun getAllSync(): List<BookTagRelation>

    /**
     * 查询同时包含所有指定标签的书籍 URL（AND 筛选）
     * @param tagIds 选中的标签 ID 列表
     * @param tagCount 标签数量，用于 HAVING COUNT(DISTINCT tagId) = :tagCount
     */
    @Query("""
        SELECT DISTINCT r.bookUrl FROM bookTagRelations r
        WHERE r.tagId IN (:tagIds)
        GROUP BY r.bookUrl
        HAVING COUNT(DISTINCT r.tagId) = :tagCount
    """)
    suspend fun getBookUrlsByAllTags(tagIds: List<Long>, tagCount: Int): List<String>

    /**
     * 观察同时包含所有指定标签的书籍 URL（AND 筛选）
     */
    @Query("""
        SELECT DISTINCT r.bookUrl FROM bookTagRelations r
        WHERE r.tagId IN (:tagIds)
        GROUP BY r.bookUrl
        HAVING COUNT(DISTINCT r.tagId) = :tagCount
    """)
    fun flowBookUrlsByAllTags(tagIds: List<Long>, tagCount: Int): Flow<List<String>>

    data class TagCount(val tagId: Long, val cnt: Int)
}
