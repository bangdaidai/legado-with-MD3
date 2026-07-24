package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagRelation
import kotlinx.coroutines.flow.Flow

@Dao
interface BookTagRelationDao {

    @Query("SELECT * FROM bookTagRelations WHERE bookUrl = :bookUrl")
    fun observeByBook(bookUrl: String): Flow<List<BookTagRelation>>

    @Query("SELECT * FROM bookTagRelations WHERE bookUrl = :bookUrl")
    suspend fun getByBook(bookUrl: String): List<BookTagRelation>

    @Transaction
    @Query("SELECT t.* FROM bookTags t INNER JOIN bookTagRelations r ON r.tagId = t.id WHERE r.bookUrl = :bookUrl")
    fun observeTagsByBook(bookUrl: String): Flow<List<BookTag>>

    @Transaction
    @Query("SELECT t.* FROM bookTags t INNER JOIN bookTagRelations r ON r.tagId = t.id WHERE r.bookUrl = :bookUrl")
    suspend fun getTagsByBook(bookUrl: String): List<BookTag>

    @Query("SELECT tagId FROM bookTagRelations WHERE bookUrl = :bookUrl")
    suspend fun getTagIdsByBook(bookUrl: String): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(relation: BookTagRelation): Long

    @Query("DELETE FROM bookTagRelations WHERE bookUrl = :bookUrl AND tagId = :tagId")
    suspend fun delete(bookUrl: String, tagId: Long)

    @Query("DELETE FROM bookTagRelations WHERE bookUrl = :bookUrl")
    suspend fun deleteByBook(bookUrl: String)

    @Query("SELECT COUNT(*) FROM bookTagRelations WHERE tagId = :tagId")
    suspend fun countBooks(tagId: Long): Int
}
