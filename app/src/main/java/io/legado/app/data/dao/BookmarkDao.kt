package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.Bookmark
import kotlinx.coroutines.flow.Flow


@Dao
interface BookmarkDao {

    @get:Query(
        """
        select * from bookmarks order by bookName collate localized, bookAuthor collate localized, chapterIndex, chapterPos
    """
    )
    val all: List<Bookmark>

    @Query("select * from bookmarks order by time desc")
    fun flowAll(): Flow<List<Bookmark>>

    @Query(
        """select * from bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        order by chapterIndex"""
    )
    fun flowByBook(bookName: String, bookAuthor: String): Flow<List<Bookmark>>

    // content like 分支必须带括号：SQLite 里 AND 优先级高于 OR，
    // 不加括号时实际语义是 (书名+作者+章节名匹配) OR (正文匹配)，
    // 「在某书里搜书签」会把全库正文命中的书签一起返回来
    @Query(
        """SELECT * FROM bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        and (chapterName like '%'||:key||'%' or content like '%'||:key||'%')
        order by chapterIndex"""
    )
    fun flowSearch(bookName: String, bookAuthor: String, key: String): Flow<List<Bookmark>>

    @Query(
        """select * from bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        order by chapterIndex"""
    )
    fun getByBook(bookName: String, bookAuthor: String): List<Bookmark>

    // 同 flowSearch：content like 分支漏了括号会跨书返回
    @Query(
        """SELECT * FROM bookmarks 
        where bookName = :bookName and bookAuthor = :bookAuthor 
        and (chapterName like '%'||:key||'%' or content like '%'||:key||'%')
        order by chapterIndex"""
    )
    fun search(bookName: String, bookAuthor: String, key: String): List<Bookmark>

    // 模糊搜索
    @Query("""
        SELECT * FROM bookmarks 
        WHERE (bookName LIKE '%'||:query||'%' 
           OR bookText LIKE '%'||:query||'%' 
           OR chapterName LIKE '%'||:query||'%' 
           OR content LIKE '%'||:query||'%')
        ORDER BY bookName COLLATE LOCALIZED, bookAuthor COLLATE LOCALIZED, chapterIndex, chapterPos
    """)
    fun flowSearchAll(query: String): Flow<List<Bookmark>>

    /** 落在某章某段位置区间内的书签，供「本页是否已有书签」判定使用。 */
    @Query(
        """select * from bookmarks
        where bookName = :bookName and bookAuthor = :bookAuthor
        and chapterIndex = :chapterIndex
        and chapterPos >= :startPos and chapterPos < :endPos"""
    )
    fun getByChapterRange(
        bookName: String,
        bookAuthor: String,
        chapterIndex: Int,
        startPos: Int,
        endPos: Int,
    ): List<Bookmark>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg bookmark: Bookmark)

    @Update
    fun update(bookmark: Bookmark)

    @Delete
    fun delete(vararg bookmark: Bookmark)

    @Query("delete from bookmarks")
    fun deleteAll()

}