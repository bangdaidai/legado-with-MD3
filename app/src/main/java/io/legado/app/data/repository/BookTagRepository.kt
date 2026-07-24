package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTag

object BookTagRepository {
    private val dao = appDb.bookTagDao

    fun observeAll() = dao.observeAll()
    suspend fun get(id: Long) = dao.get(id)
    suspend fun getByName(name: String) = dao.getByName(name)
    suspend fun insert(tag: BookTag): Long = dao.insert(tag)
    suspend fun update(tag: BookTag) = dao.update(tag)
    suspend fun delete(tag: BookTag) = dao.delete(tag)
    suspend fun deleteById(id: Long) = dao.deleteById(id)
    suspend fun countBooks(tagId: Long): Int = appDb.bookTagRelationDao.countBooks(tagId)
}
