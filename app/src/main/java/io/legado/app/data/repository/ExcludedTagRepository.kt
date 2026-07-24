package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.data.entities.ExcludedTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

object ExcludedTagRepository {
    private val dao = appDb.excludedTagDao

    fun observeAll(): Flow<List<ExcludedTag>> = dao.observeAll()

    suspend fun getByName(name: String) = dao.getByName(name)

    suspend fun add(name: String) {
        if (dao.getByName(name) == null) {
            dao.insert(ExcludedTag(name = name))
        }
    }

    suspend fun remove(name: String) = dao.deleteByName(name)

    /** 当前排除的标签名称集合，供书架/标签选择过滤使用 */
    suspend fun excludedNames(): Set<String> =
        dao.observeAll().first().map { it.name }.toSet()
}
