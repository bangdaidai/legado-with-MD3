package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.legado.app.data.entities.BookCastMemory

@Dao
interface BookCastMemoryDao {

    @Query("SELECT * FROM book_cast_memory WHERE bookUrl = :bookUrl")
    suspend fun get(bookUrl: String): BookCastMemory?

    /** 备份/恢复用：全表导出与批量回写（主键 bookUrl，@Upsert 幂等覆盖）。 */
    @Query("SELECT * FROM book_cast_memory")
    suspend fun getAll(): List<BookCastMemory>

    @Upsert
    suspend fun upsert(memory: BookCastMemory)

    @Upsert
    suspend fun insertAll(memories: List<BookCastMemory>)

    @Query("DELETE FROM book_cast_memory WHERE bookUrl = :bookUrl")
    suspend fun delete(bookUrl: String)
}
