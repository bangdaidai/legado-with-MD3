package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.data.entities.ExcludedTag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

object ExcludedTagRepository {
    private val dao = appDb.excludedTagDao

    fun observeAll(): Flow<List<ExcludedTag>> = dao.observeAll()

    suspend fun getByName(name: String) = dao.getByName(name)

    suspend fun add(name: String, isRegex: Boolean = false) {
        val t = name.trim()
        if (t.isNotEmpty() && dao.getByName(t) == null) {
            dao.insert(ExcludedTag(name = t, isRegex = isRegex))
        }
    }

    suspend fun remove(name: String) = dao.deleteByName(name)

    /** 判断给定标签名是否被排除（精确匹配或正则匹配） */
    suspend fun isExcluded(name: String): Boolean =
        dao.observeAll().first().any { ex ->
            if (ex.isRegex) {
                runCatching { Regex(ex.name).containsMatchIn(name) }.getOrDefault(false)
            } else {
                ex.name == name
            }
        }

    /** 当前排除的标签名称集合，供书架/标签选择过滤使用 */
    suspend fun excludedNames(): Set<String> =
        dao.observeAll().first().map { it.name }.toSet()
}
