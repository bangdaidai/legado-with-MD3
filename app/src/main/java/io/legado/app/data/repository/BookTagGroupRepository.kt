package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookTagGroup

object BookTagGroupRepository {
    private val dao = appDb.bookTagGroupDao

    fun observeAll() = dao.observeAll()
    suspend fun get(id: Long) = dao.get(id)
    suspend fun getByName(name: String) = dao.getByName(name)
    suspend fun insert(group: BookTagGroup): Long = dao.insert(group)
    suspend fun update(group: BookTagGroup) = dao.update(group)
    suspend fun delete(group: BookTagGroup) = dao.delete(group)
    suspend fun deleteById(id: Long) = dao.deleteById(id)
}
