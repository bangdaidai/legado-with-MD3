package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.BookMarking
import kotlinx.coroutines.flow.Flow

@Dao
interface BookMarkingDao {

    /**
     * 按创建时的源（bookUrl）查：渲染只画当前源能对上正文的标记。
     * 与 bookmarks 不同，book_marks 认「书名+作者」跨源关联，列表见 [flowByBook]。
     */
    @Query(
        """
        select * from book_marks
        where bookUrl = :bookUrl
          and (:chapterIndex is null or chapterIndex is null or chapterIndex = :chapterIndex)
        order by createdAt
        """
    )
    fun getForChapterSync(bookUrl: String, chapterIndex: Int?): List<BookMarking>

    /** 按「书名+作者」查（含跨源全部标记），供保存去重/定位；chapterIndex 可空。 */
    @Query(
        """
        select * from book_marks
        where bookName = :bookName and bookAuthor = :bookAuthor
          and (:chapterIndex is null or chapterIndex is null or chapterIndex = :chapterIndex)
        order by createdAt
        """
    )
    suspend fun getByBook(
        bookName: String,
        bookAuthor: String,
        chapterIndex: Int?
    ): List<BookMarking>

    /** 按「书名+作者」流式订阅全部章节的标记，供目录 Sheet 笔记页跨源展示。 */
    @Query(
        """
        select * from book_marks
        where bookName = :bookName and bookAuthor = :bookAuthor
        order by chapterIndex, createdAt
        """
    )
    fun flowByBook(bookName: String, bookAuthor: String): Flow<List<BookMarking>>

    @Query("select * from book_marks order by updatedAt desc")
    fun flowAll(): Flow<List<BookMarking>>

    /** 全量取出，供备份导出（Flow 不适用于一次性导出）。 */
    @Query("select * from book_marks order by createdAt")
    suspend fun getAllSync(): List<BookMarking>


    /** 单本书的笔记数，供阅读记忆 annotationCount 统计。 */
    @Query("select count(*) from book_marks where bookName = :bookName and bookAuthor = :bookAuthor")
    suspend fun countByBook(bookName: String, bookAuthor: String): Int

    /** 全库笔记总数，供阅读总览统计。 */
    @Query("select count(*) from book_marks")
    fun flowTotalCount(): Flow<Int>


    @Query("select * from book_marks where id = :id")
    suspend fun getById(id: String): BookMarking?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(bookMarking: BookMarking)

    /** 批量写入，供备份恢复；主键冲突按 REPLACE，重复恢复不会产生重复笔记。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bookMarkings: List<BookMarking>)


    @Query("update book_marks set enabled = :enabled, updatedAt = :updatedAt where id = :id")
    suspend fun setEnabled(
        id: String,
        enabled: Boolean,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("delete from book_marks where id = :id")
    suspend fun delete(id: String)

    /**
     * 换源后把标记挪到新 bookUrl。
     *
     * 渲染查询 [getForChapterSync] 按 bookUrl 过滤，不迁移的话换源后会出现
     * 「列表里笔记还在、计数也对，正文一条高亮都画不出来」且无任何提示。
     * 主键是 id，直接 update 即可，不像 readingMemory 那样要先复制再删旧行。
     * 不动 updatedAt：这是换源引起的搬迁，不是用户编辑，改了会把笔记顶到
     * 「最近更新」最前面（[flowAll] 按 updatedAt 排序）。
     */
    @Query("update book_marks set bookUrl = :newBookUrl where bookUrl = :oldBookUrl")
    suspend fun migrateToNewBookUrl(oldBookUrl: String, newBookUrl: String)
}
