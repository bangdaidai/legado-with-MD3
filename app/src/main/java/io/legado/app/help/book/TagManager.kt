package io.legado.app.help.book

import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagRelation
import io.legado.app.data.repository.ExcludedTagRepository
import io.legado.app.utils.TagColorUtils
import io.legado.app.utils.eventBus.FlowEventBus

/**
 * 书籍标签管理器（MD3 精简版）。
 * 融合 readdai 的标签库能力：标签具备颜色/分组，书籍与标签为多对多关系。
 * 依赖 BookTag / BookTagGroup / BookTagRelation，并通过 ExcludedTag 实现排除标签过滤。
 */
object TagManager {

    /** 确保标签存在（按名称查找，不存在则创建），返回标签实体 */
    suspend fun ensureTag(name: String, color: Int? = null): BookTag {
        val exist = appDb.bookTagDao.getByName(name)
        if (exist != null) return exist
        val tag = BookTag(name = name, color = color ?: TagColorUtils.generateRandomColor(name))
        val id = appDb.bookTagDao.insert(tag)
        return appDb.bookTagDao.get(id)!!
    }

    /** 设置书籍的全部标签（按名称），替换原有关系 */
    suspend fun setBookTags(bookUrl: String, tagNames: List<String>) {
        val tags = tagNames.map { ensureTag(it) }
        val relationDao = appDb.bookTagRelationDao
        relationDao.deleteByBook(bookUrl)
        tags.forEach {
            relationDao.insert(
                BookTagRelation(
                    id = BookTagRelation.generateId(),
                    bookUrl = bookUrl,
                    tagId = it.id
                )
            )
        }
        postUpdated(bookUrl)
    }

    suspend fun addTagToBook(bookUrl: String, tagId: Long) {
        appDb.bookTagRelationDao.insert(
            BookTagRelation(
                id = BookTagRelation.generateId(),
                bookUrl = bookUrl,
                tagId = tagId
            )
        )
        postUpdated(bookUrl)
    }

    suspend fun removeTagFromBook(bookUrl: String, tagId: Long) {
        appDb.bookTagRelationDao.delete(bookUrl, tagId)
        postUpdated(bookUrl)
    }

    /** 获取某书籍关联的全部标签（已剔除排除标签） */
    suspend fun getBookTags(bookUrl: String): List<BookTag> {
        val ids = appDb.bookTagRelationDao.getTagIdsByBook(bookUrl)
        val tags = ids.mapNotNull { appDb.bookTagDao.get(it) }
        val excluded = ExcludedTagRepository.excludedNames()
        return tags.filter { it.name !in excluded }
    }

    private fun postUpdated(bookUrl: String) {
        FlowEventBus.post(EventBus.TAGS_UPDATED, bookUrl)
    }
}
