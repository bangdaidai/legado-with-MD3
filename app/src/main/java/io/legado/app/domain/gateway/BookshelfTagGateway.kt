package io.legado.app.domain.gateway

import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.ExcludedTag
import kotlinx.coroutines.flow.Flow

/**
 * 书架标签筛选相关的 Gateway，封装标签 DAO 访问
 */
interface BookshelfTagGateway {
    /** 观察展示在书架标签筛选中的标签列表 */
    fun observeShowOnBookshelf(): Flow<List<BookTag>>

    /** 观察同时包含所有指定标签的书籍 URL（AND 筛选） */
    fun flowBookUrlsByAllTags(tagIds: List<Long>, tagCount: Int): Flow<List<String>>

    /** 观察所有标签（用于标签颜色映射） */
    fun observeAllBookTags(): Flow<List<BookTag>>

    /** 观察所有排除标签规则 */
    fun observeAllExcludedTags(): Flow<List<ExcludedTag>>

    /** 根据 ID 列表获取标签 */
    suspend fun getTagsByIds(ids: List<Long>): List<BookTag>

    /** 批量更新标签的展示在书架状态 */
    suspend fun batchUpdateShowOnBookshelf(tagIds: Set<Long>, show: Boolean): Int
}
