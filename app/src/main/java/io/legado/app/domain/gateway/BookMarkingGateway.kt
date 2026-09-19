package io.legado.app.domain.gateway

import io.legado.app.data.entities.BookMarking
import kotlinx.coroutines.flow.Flow

interface BookMarkingGateway {
    /** 按「书名+作者」查，供保存去重/定位；chapterIndex 可空。 */
    suspend fun getByBook(
        bookName: String,
        bookAuthor: String,
        chapterIndex: Int?
    ): List<BookMarking>

    /** 按「书名+作者」流式订阅全部章节，供目录 Sheet 笔记页跨源展示。 */
    fun flowByBook(bookName: String, bookAuthor: String): Flow<List<BookMarking>>

    /** 流式订阅全部笔记，供「所有笔记」页跨书展示。 */
    fun flowAll(): Flow<List<BookMarking>>

    /** 单本书的笔记数，供阅读记忆 annotationCount 统计。 */
    suspend fun countByBook(bookName: String, bookAuthor: String): Int

    /** 全库笔记总数，供阅读总览统计。 */
    fun flowTotalCount(): Flow<Int>


    suspend fun getById(id: String): BookMarking?
    suspend fun upsert(bookMarking: BookMarking)
    suspend fun setEnabled(id: String, enabled: Boolean)
    suspend fun delete(id: String)
}
