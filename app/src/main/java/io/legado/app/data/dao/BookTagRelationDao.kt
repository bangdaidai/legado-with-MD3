package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.BookTagRelation

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

    @Query("SELECT COUNT(DISTINCT bookUrl) FROM bookTagRelations WHERE tagId = :tagId")
    suspend fun countBooksByTagId(tagId: Long): Int

    @Query("SELECT tagId, COUNT(DISTINCT bookUrl) AS cnt FROM bookTagRelations GROUP BY tagId")
    suspend fun countAllByTag(): List<TagCount>

    @Query("SELECT * FROM bookTagRelations")
    suspend fun getAllSync(): List<BookTagRelation>

    data class TagCount(val tagId: Long, val cnt: Int)
}
