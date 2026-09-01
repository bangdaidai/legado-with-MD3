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
}
