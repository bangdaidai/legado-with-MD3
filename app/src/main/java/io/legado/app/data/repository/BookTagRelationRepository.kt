package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTagRelation

object BookTagRelationRepository {
    private val dao = appDb.bookTagRelationDao

    fun observeByBook(bookUrl: String) = dao.observeByBook(bookUrl)
    fun observeTagsByBook(bookUrl: String) = dao.observeTagsByBook(bookUrl)
    suspend fun getByBook(bookUrl: String) = dao.getByBook(bookUrl)
    suspend fun getTagIdsByBook(bookUrl: String) = dao.getTagIdsByBook(bookUrl)
    suspend fun insert(relation: BookTagRelation) = dao.insert(relation)
    suspend fun delete(bookUrl: String, tagId: Long) = dao.delete(bookUrl, tagId)
    suspend fun deleteByBook(bookUrl: String) = dao.deleteByBook(bookUrl)
    suspend fun countBooks(tagId: Long) = dao.countBooks(tagId)
}
