package io.legado.app.domain.gateway

import io.legado.app.data.entities.BookTag
import kotlinx.coroutines.flow.Flow

/**
 * 书架标签筛选相关的 Gateway，封装标签 DAO 访问
 */
interface BookshelfTagGateway {
    /** 观察展示在书架标签筛选中的标签列表 */
    fun observeShowOnBookshelf(): Flow<List<BookTag>>

    /** 观察同时包含所有指定标签的书籍 URL（AND 筛选） */
    fun flowBookUrlsByAllTags(tagIds: List<Long>, tagCount: Int): Flow<List<String>>
}
