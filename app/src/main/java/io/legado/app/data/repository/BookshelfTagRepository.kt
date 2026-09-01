package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTag
import io.legado.app.domain.gateway.BookshelfTagGateway
import kotlinx.coroutines.flow.Flow

/**
 * 书架标签筛选 Gateway 的实现，委托给 BookTagDao 和 BookTagRelationDao
 */
class BookshelfTagRepository : BookshelfTagGateway {

    override fun observeShowOnBookshelf(): Flow<List<BookTag>> =
        appDb.bookTagDao.observeShowOnBookshelf()

    override fun flowBookUrlsByAllTags(tagIds: List<Long>, tagCount: Int): Flow<List<String>> =
        appDb.bookTagRelationDao.flowBookUrlsByAllTags(tagIds, tagCount)
}
