package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.ExcludedTag
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

    override fun observeAllBookTags(): Flow<List<BookTag>> =
        appDb.bookTagDao.observeAll()

    override fun observeAllExcludedTags(): Flow<List<ExcludedTag>> =
        appDb.excludedTagDao.observeAll()

    override suspend fun getTagsByIds(ids: List<Long>): List<BookTag> =
        appDb.bookTagDao.getByIds(ids)

    override suspend fun batchUpdateShowOnBookshelf(tagIds: Set<Long>, show: Boolean): Int {
        if (tagIds.isEmpty()) return 0
        val tags = appDb.bookTagDao.getByIds(tagIds.toList())
        var updatedCount = 0
        for (tag in tags) {
            if (tag.showOnBookshelf != show) {
                appDb.bookTagDao.update(tag.copy(showOnBookshelf = show, updateTime = System.currentTimeMillis()))
                updatedCount++
            }
        }
        return updatedCount
    }
}
