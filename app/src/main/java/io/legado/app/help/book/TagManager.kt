package io.legado.app.help.book

import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookTag
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.entities.BookTagRelation
import io.legado.app.data.entities.ExcludedTag
import io.legado.app.data.entities.TagMapping
import io.legado.app.help.config.AppConfigStore
import io.legado.app.utils.postEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import kotlin.random.Random

/**
 * 标签管理核心逻辑（移植自 readdai，逻辑层与 UI 无关）。
 * 负责：从书籍 kind 解析标签、排除标签匹配、标签映射(异名归一)、换源时重算标签、计数与列表加载。
 */
object TagManager {

    // 标签默认配色（MD3 风格，用于在用户未选色时给标签一个稳定颜色）
    private val TAG_COLORS = listOf(
        0xFF6750A4, 0xFF625B71, 0xFF7D5260, 0xFFB3261E,
        0xFF835785, 0xFF386A20, 0xFF006A6A, 0xFF00558E,
        0xFF8C5B00, 0xFF4A4458, 0xFF1C1B1F, 0xFF49454F,
        0xFF9C27B0, 0xFF2196F3, 0xFF00897B, 0xFFEF6C00,
    ).map { it.toLong() }

    /** 根据标签名生成稳定的颜色（基于哈希，保证同名标签颜色一致）。 */
    fun generateTagColor(name: String): Long {
        val hash = name.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
        return TAG_COLORS[hash % TAG_COLORS.size]
    }

    /** 正版书源判定：书源分组包含「正版」即为正版源。 */
    fun isOfficialSource(bookSource: BookSource?): Boolean {
        return bookSource?.bookSourceGroup?.contains("正版") == true
    }

    /** 排除标签匹配：普通关键字 contains / 正则 find（忽略大小写，部分匹配）。
     * 注意：正则必须用 find() 而非 matches()，matches() 要求整串完全匹配，
     * 用户写「含数字的标签排除」这类规则（如 \d+）会永远不命中。 */
    fun isExcluded(tagName: String, excludedTags: List<ExcludedTag>): Boolean {
        for (excluded in excludedTags) {
            if (excluded.isRegex) {
                try {
                    if (Pattern.compile(excluded.name, Pattern.CASE_INSENSITIVE).matcher(tagName).find()) {
                        return true
                    }
                } catch (_: PatternSyntaxException) {
                    // 非法正则忽略
                }
            } else {
                if (tagName.contains(excluded.name, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    /** 预置排除规则（仅首次启动播种一次，用户可自由编辑/删除）：
     *  \d+ —— 含任意数字的标签（如「玄幻123」「完结2023」）一律排除。 */
    suspend fun seedPresetExcludedTags() {
        val key = "excludedTagPresetSeeded"
        if (AppConfigStore.getBoolean(key) == true) return
        val presets = listOf(
            ExcludedTag(name = "\\d+", isRegex = true),
        )
        val existing = appDb.excludedTagDao.getAllSync()
        for (preset in presets) {
            if (existing.none { it.name == preset.name && it.isRegex == preset.isRegex }) {
                appDb.excludedTagDao.insert(preset)
            }
        }
        AppConfigStore.putBoolean(key, true)
    }

    /** 回溯应用排除规则：扫描当前所有已存在标签，凡名称命中排除规则者，
     *  删除其全部关联并删除标签本身（而非仅对未来生成生效）。
     *  每次调用幂等，返回被移除的标签数量。 */
    suspend fun applyExclusionToExistingTags(): Int {
        val excludedTags = appDb.excludedTagDao.getAllSync()
        if (excludedTags.isEmpty()) return 0
        val allTags = appDb.bookTagDao.getAllSync()
        val toRemove = allTags.filter { isExcluded(it.name, excludedTags) }
        if (toRemove.isEmpty()) return 0
        for (tag in toRemove) {
            appDb.bookTagRelationDao.deleteByTagId(tag.id)
            appDb.bookTagDao.deleteById(tag.id)
        }
        postEvent(EventBus.TAGS_UPDATED, "exclusion_backfill")
        return toRemove.size
    }

    /** 排除规则变更后的统一对账：先按当前规则移除命中的现有标签，
     *  再从所有书籍 kind 重新生成标签，自动恢复因规则被删除/缩小而不再被排除的标签。
     *  全程自动（无需手动重新同步书架），返回移除/恢复数量。 */
    suspend fun reconcileTagsWithExclusion(): ReconcileResult {
        val excludedTags = appDb.excludedTagDao.getAllSync()
        val allTags = appDb.bookTagDao.getAllSync()
        val toRemove = allTags.filter { isExcluded(it.name, excludedTags) }
        for (tag in toRemove) {
            appDb.bookTagRelationDao.deleteByTagId(tag.id)
            appDb.bookTagDao.deleteById(tag.id)
        }
        // 从书籍 kind 重新生成：凡不再被排除的标签会自动恢复
        try {
            val books = appDb.bookDao.all
            for (book in books) {
                generateTagsFromKind(book, postEvent = false)
            }
        } catch (_: Exception) {
            // 重新生成失败不影响已完成的移除与刷新
        }
        val afterNames = appDb.bookTagDao.getAllSync().map { it.name }.toSet()
        val restored = toRemove.count { it.name in afterNames }
        postEvent(EventBus.TAGS_UPDATED, "exclusion_reconcile")
        return ReconcileResult(removed = toRemove.size, restored = restored)
    }

    data class ReconcileResult(val removed: Int, val restored: Int)

    /** 将书籍 kind（逗号/换行分隔的分类）解析为标签，并写入 bookTagRelations（含标签映射异名归一）。
     * @param postEvent 是否发出 TAGS_UPDATED 事件，批量同步时传 false 避免重入循环 */
    suspend fun generateTagsFromKind(book: Book, postEvent: Boolean = true): List<BookTag> {
        val kind = book.kind ?: return emptyList()
        val candidates = kind.split(",", "\n", "，", "、", "|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (candidates.isEmpty()) return emptyList()

        val excludedTags = appDb.excludedTagDao.getAllSync()
        val tagMappings = appDb.tagMappingDao.getAll()
        val mappingByOldName = tagMappings.associateBy { it.oldTagName }

        // 第一遍：解析出最终标签（可能含未持久化的新标签 id=0）
        val resolvedTargets = mutableListOf<BookTag>()
        val newTagNames = linkedSetOf<String>()
        for (candidate in candidates) {
            val normalized = candidate.lowercase().trim()
            if (isExcluded(candidate, excludedTags)) continue

            val mapping = mappingByOldName[normalized] ?: mappingByOldName[candidate]
            val targetTag = if (mapping != null) {
                appDb.bookTagDao.getByIds(listOf(mapping.newTagId)).firstOrNull()
                    ?: createNewTag(candidate)
            } else {
                createNewTag(candidate)
            }
            resolvedTargets.add(targetTag)
            if (targetTag.id == 0L) newTagNames.add(targetTag.name)
        }

        // 批量持久化新标签，建立 名称→带 id 的标签 映射
        val nameToTag = mutableMapOf<String, BookTag>()
        if (newTagNames.isNotEmpty()) {
            val toCreate = newTagNames.map { BookTag(name = it, color = generateTagColor(it)) }
            val ids = appDb.bookTagDao.insertAll(toCreate)
            toCreate.forEachIndexed { index, t -> nameToTag[t.name] = t.copy(id = ids[index]) }
        }

        // 解析出带真实 id 的最终标签（去重）
        val finalTags = resolvedTargets.map { ft ->
            if (ft.id != 0L) ft else nameToTag[ft.name] ?: ft
        }.distinctBy { it.id }

        // 写入关系（跳过已存在的关联）
        val existingRelations =
            appDb.bookTagRelationDao.getByBookUrl(book.bookUrl).associateBy { it.tagId }
        val relationsToInsert = finalTags.filter { !existingRelations.containsKey(it.id) }
            .map { target ->
                BookTagRelation(
                    id = "relation_${System.currentTimeMillis()}_${Random.nextInt(100000)}",
                    bookUrl = book.bookUrl,
                    tagId = target.id,
                )
            }
        if (relationsToInsert.isNotEmpty()) {
            appDb.bookTagRelationDao.insertAll(relationsToInsert)
        }
        if (postEvent) postEvent(EventBus.TAGS_UPDATED, book.bookUrl)
        return finalTags
    }

    /**
     * 将同名标签（别名）合并进标准标签：把别名标签的书籍关联转移到标准标签，
     * 并删除别名标签本身，使其从标签页消失。与 readdai 的 replaceTag 行为一致。
     *
     * @param aliasName    别名（旧标签名），例如「现代玄幻」
     * @param standardTagId 标准标签的 id（映射目标），例如「玄幻」
     */
    suspend fun mergeAliasTagInto(aliasName: String, standardTagId: Long) =
        withContext(Dispatchers.IO) {
            val aliasTag = appDb.bookTagDao.getByName(aliasName) ?: return@withContext
            if (aliasTag.id == standardTagId) return@withContext
            val relations = appDb.bookTagRelationDao.getByTagId(aliasTag.id)
            for (rel in relations) {
                if (appDb.bookTagRelationDao.getRelation(rel.bookUrl, standardTagId) == null) {
                    appDb.bookTagRelationDao.insert(
                        BookTagRelation(
                            id = "relation_${System.currentTimeMillis()}_${Random.nextInt(100000)}",
                            bookUrl = rel.bookUrl,
                            tagId = standardTagId,
                        )
                    )
                }
            }
            appDb.bookTagRelationDao.deleteByTagId(aliasTag.id)
            appDb.bookTagDao.deleteById(aliasTag.id)
            postEvent(EventBus.TAGS_UPDATED, standardTagId)
        }

    private suspend fun createNewTag(name: String): BookTag {
        val existing = appDb.bookTagDao.getByNames(listOf(name)).firstOrNull()
        return if (existing != null) {
            existing
        } else {
            BookTag(name = name, color = generateTagColor(name))
        }
    }

    /** 换源时重算标签：先删除旧关联，再按新 kind 重新生成。 */
    suspend fun updateTagsOnSourceChange(book: Book) {
        withContext(Dispatchers.IO) {
            appDb.bookTagRelationDao.deleteByBookUrl(book.bookUrl)
            generateTagsFromKind(book)
        }
    }

    /** 加载某本书关联的全部标签。 */
    suspend fun loadBookTags(book: Book): List<BookTag> {
        val relations = appDb.bookTagRelationDao.getByBookUrl(book.bookUrl)
        if (relations.isEmpty()) return emptyList()
        val tagIds = relations.map { it.tagId }
        return appDb.bookTagDao.getByIds(tagIds)
    }

    /** 计算单个标签关联的书籍数。 */
    suspend fun getTagBookCount(tagId: Long): Int {
        return appDb.bookTagRelationDao.countBooksByTagId(tagId)
    }

    /** 批量计算标签计数，返回 Map<tagId, count>。 */
    suspend fun getTagBookCounts(): Map<Long, Int> {
        return appDb.bookTagRelationDao.countAllByTag().associate { it.tagId to it.cnt }
    }

    /** 全部分组（按排序）。 */
    suspend fun getAllGroups(): List<BookTagGroup> {
        return appDb.bookTagGroupDao.getAllSorted()
    }

    /** 判断正则是否合法（供 UI 校验）。 */
    fun isValidRegex(pattern: String): Boolean {
        return try {
            Pattern.compile(pattern)
            true
        } catch (_: PatternSyntaxException) {
            false
        }
    }
}
