package io.legado.app.data.repository

import android.text.TextUtils
import io.legado.app.data.dao.ReplaceRuleDao
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.help.book.ContentProcessor
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ReplaceRuleRepository(
    private val dao: ReplaceRuleDao,
) {

    /**
     * [ContentProcessor] 把每本书生效的规则缓存在内存里，只在被通知时重读。规则写库后不刷新，
     * 目录、正文、朗读都还在用旧规则——原先只有阅读页和书源导入记得手动刷，从替换净化管理页
     * 改完规则其它页面全都看不到效果。所以在写入出口统一失效，调用方不必各自记得。
     *
     * 只分组名变化的操作不调用：`group` 不参与匹配和作用范围判断。
     */
    private fun invalidateProcessors() {
        runCatching { ContentProcessor.upReplaceRules() }
    }


    fun flowGroups(): Flow<List<String>> {
        return dao.flowGroups().flowOn(Dispatchers.IO)
    }

    fun flowAll(): Flow<List<ReplaceRule>> {
        return dao.flowAll().flowOn(Dispatchers.IO)
    }

    /**
     * 规则内容指纹。规则表由管理页、编辑页、导入、AI 采用等多个入口写入，页面靠 onResume
     * 之类的时机去重查并不可靠；Room 的失效通知能覆盖所有写入方，是唯一可信的信号源。
     *
     * 不能直接对 `List<ReplaceRule>` 做 `distinctUntilChanged`：[ReplaceRule.hashCode] 只取 id，
     * 改 pattern/replacement 前后两个列表会被判定相同。所以显式把参与替换和作用范围判断的字段
     * 折进指纹；`name`、`group` 这些只影响展示的字段不参与，避免改个名字就让目录重算。
     */
    fun flowContentSignature(): Flow<Int> {
        return flowAll().map { rules ->
            rules.fold(1) { acc, rule ->
                var hash = acc
                hash = 31 * hash + rule.id.hashCode()
                hash = 31 * hash + rule.pattern.hashCode()
                hash = 31 * hash + rule.replacement.hashCode()
                hash = 31 * hash + rule.isRegex.hashCode()
                hash = 31 * hash + rule.isEnabled.hashCode()
                hash = 31 * hash + rule.scopeTitle.hashCode()
                hash = 31 * hash + rule.scopeContent.hashCode()
                hash = 31 * hash + rule.scope.hashCode()
                hash = 31 * hash + rule.excludeScope.hashCode()
                hash = 31 * hash + rule.order
                hash = 31 * hash + rule.timeoutMillisecond.hashCode()
                hash
            }
        }.distinctUntilChanged()
    }

    fun flowNoGroup(): Flow<List<ReplaceRule>> {
        return dao.flowNoGroup().flowOn(Dispatchers.IO)
    }

    fun flowGroupSearch(key: String): Flow<List<ReplaceRule>> {
        return dao.flowGroupSearch(key).flowOn(Dispatchers.IO)
    }

    fun flowSearch(key: String): Flow<List<ReplaceRule>> {
        return dao.flowSearch(key).flowOn(Dispatchers.IO)
    }

    suspend fun findById(id: Long): ReplaceRule? = withContext(Dispatchers.IO) {
        dao.findById(id)
    }

    suspend fun getNextOrder(): Int = withContext(Dispatchers.IO) {
        dao.maxOrder + 1
    }

    suspend fun update(vararg rule: ReplaceRule) {
        withContext(Dispatchers.IO) {
            dao.update(*rule)
            invalidateProcessors()
        }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            dao.updateEnabled(id, enabled)
            invalidateProcessors()
        }
    }

    suspend fun insert(vararg rule: ReplaceRule) {
        withContext(Dispatchers.IO) {
            dao.insert(*rule)
            invalidateProcessors()
        }
    }

    suspend fun delete(rule: ReplaceRule) {
        withContext(Dispatchers.IO) {
            dao.delete(rule)
            invalidateProcessors()
        }
    }


    suspend fun toTop(rule: ReplaceRule, isDesc: Boolean = false) {
        withContext(Dispatchers.IO) {
            if (isDesc) {
                rule.order = dao.maxOrder + 1
            } else {
                rule.order = dao.minOrder - 1
            }
            dao.update(rule)
            invalidateProcessors()
        }
    }

    suspend fun toBottom(rule: ReplaceRule, isDesc: Boolean = false) {
        withContext(Dispatchers.IO) {
            if (isDesc) {
                rule.order = dao.minOrder - 1
            } else {
                rule.order = dao.maxOrder + 1
            }
            dao.update(rule)
            invalidateProcessors()
        }
    }


    suspend fun upOrder() {
        withContext(Dispatchers.IO) {
            val rules = dao.all
            var normalOrder = 1
            rules.forEach { rule ->
                if (rule.order >= 0) {
                    rule.order = normalOrder++
                }
            }
            dao.update(*rules.toTypedArray())
            invalidateProcessors()
        }
    }

    suspend fun addGroup(group: String) {
        withContext(Dispatchers.IO) {
            val sources = dao.noGroup
            sources.forEach { source ->
                source.group = group
            }
            dao.update(*sources.toTypedArray())
        }
    }

    suspend fun upGroup(oldGroup: String, newGroup: String?) {
        withContext(Dispatchers.IO) {
            val sources = dao.getByGroup(oldGroup)
            sources.forEach { source ->
                source.group?.splitNotBlank(",")?.toHashSet()?.let {
                    it.remove(oldGroup)
                    if (!newGroup.isNullOrEmpty())
                        it.add(newGroup)
                    source.group = TextUtils.join(",", it)
                }
            }
            dao.update(*sources.toTypedArray())
        }
    }

    suspend fun delGroup(group: String) {
        withContext(Dispatchers.IO) {
            val sources = dao.getByGroup(group)
            sources.forEach { source ->
                source.group?.splitNotBlank(",")?.toHashSet()?.let {
                    it.remove(group)
                    source.group = TextUtils.join(",", it)
                }
            }
            dao.update(*sources.toTypedArray())
        }
    }

    suspend fun clearGroups(groups: List<String>) = withContext(Dispatchers.IO) {
        dao.clearGroups(groups)
    }

    suspend fun enableByIds(ids: Set<Long>) =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext
            dao.updateEnabled(ids.toList(), true)
            invalidateProcessors()
        }

    suspend fun disableByIds(ids: Set<Long>) =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext
            dao.updateEnabled(ids.toList(), false)
            invalidateProcessors()
        }

    suspend fun deleteByIds(ids: Set<Long>) =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext

            val rules = dao.getByIds(ids)
            dao.delete(*rules.toTypedArray())
            invalidateProcessors()
        }


    suspend fun topByIds(ids: Set<Long>, isDesc: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext
            val rules = dao.getByIds(ids)
            if (isDesc) {
                var maxOrder = dao.maxOrder
                val updated = rules.map {
                    maxOrder++
                    it.copy(order = maxOrder)
                }
                dao.update(*updated.toTypedArray())
            } else {
                var minOrder = dao.minOrder
                val updated = rules.map {
                    minOrder--
                    it.copy(order = minOrder)
                }
                dao.update(*updated.toTypedArray())
            }
            invalidateProcessors()
        }


    suspend fun bottomByIds(ids: Set<Long>, isDesc: Boolean = false) =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext

            val rules = dao.getByIds(ids)
            if (isDesc) {
                var minOrder = dao.minOrder
                val updated = rules.map {
                    minOrder--
                    it.copy(order = minOrder)
                }
                dao.update(*updated.toTypedArray())
            } else {
                var maxOrder = dao.maxOrder
                val updated = rules.map {
                    maxOrder++
                    it.copy(order = maxOrder)
                }
                dao.update(*updated.toTypedArray())
            }
            invalidateProcessors()
        }


    /**
     * 把 [draggedId] 规则移动到 [anchorId] 规则旁边（[afterAnchor] 为 true 时在其后，否则在其前）。
     * 列表顺序始终按 sortOrder 升序，移动后统一重写全部规则序号。
     */
    suspend fun moveReplaceRule(draggedId: Long, anchorId: Long, afterAnchor: Boolean) {
        withContext(Dispatchers.IO) {
            val rules = dao.all
            val draggedIndex = rules.indexOfFirst { it.id == draggedId }
            if (draggedIndex < 0) return@withContext
            val dragged = rules[draggedIndex]
            val remaining = rules.toMutableList().apply { removeAt(draggedIndex) }
            val anchorIndex = remaining.indexOfFirst { it.id == anchorId }
            if (anchorIndex < 0) return@withContext
            val insertIndex = (anchorIndex + if (afterAnchor) 1 else 0).coerceIn(0, remaining.size)
            remaining.add(insertIndex, dragged)
            val updated = remaining.mapIndexed { index, rule -> rule.copy(order = index + 1) }
            dao.update(*updated.toTypedArray())
            invalidateProcessors()
        }
    }

    suspend fun moveOrder(currentRules: List<ReplaceRule>, isDesc: Boolean = false) {
        withContext(Dispatchers.IO) {
            val size = currentRules.size
            val updatedRules = currentRules.mapIndexed { index, rule ->
                val order = if (isDesc) size - index else index + 1
                rule.copy(order = order)
            }
            dao.update(*updatedRules.toTypedArray())
            invalidateProcessors()
        }
    }

}
