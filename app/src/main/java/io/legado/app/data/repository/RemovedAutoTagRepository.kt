package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.data.entities.RemovedAutoTag
import kotlinx.coroutines.flow.Flow

object RemovedAutoTagRepository {
    private val dao = appDb.removedAutoTagDao

    fun observeAll(): Flow<List<RemovedAutoTag>> = dao.observeAll()

    suspend fun getByName(name: String, type: Int = 0) = dao.getByName(name, type)

    suspend fun add(name: String, type: Int = 0) {
        if (dao.getByName(name, type) == null) {
            dao.insert(RemovedAutoTag(name = name, type = type))
        }
    }

    suspend fun remove(name: String) = dao.deleteByName(name)
}
