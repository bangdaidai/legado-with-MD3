package io.legado.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import io.legado.app.data.AppDatabase
import io.legado.app.data.dao.BookDao
import io.legado.app.data.dao.BookKnowledgeDao
import io.legado.app.data.dao.BookmarkDao
import io.legado.app.data.dao.ReadRecordDao
import io.legado.app.data.dao.ReadingMemoryDao
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.ExcludedTag
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordTimelineDay
import io.legado.app.data.repository.ReadRecordRepository
import io.legado.app.help.book.ProtagonistExtractor
import io.legado.app.help.book.TagManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.Calendar
import java.util.UUID
import io.legado.app.constant.AppLog

class ReadingMemoryRepository(
    private val dao: ReadingMemoryDao,
    private val bookDao: BookDao,
    private val bookKnowledgeDao: BookKnowledgeDao,
    private val bookmarkDao: BookmarkDao,
    private val readRecordDao: ReadRecordDao,
    private val database: AppDatabase,
    private val readRecordRepository: ReadRecordRepository,
) {

    companion object {
        private const val DEVICE_ID = ""
    }

    // region 基础查询

    fun observeAll(): Flow<List<ReadingMemory>> = dao.getAll()

    fun observeByBookUrl(bookUrl: String): Flow<ReadingMemory?> =
        dao.getByBookUrl(bookUrl).onEach {
            AppLog.put("[阅读记忆] observeByBookUrl bookUrl=$bookUrl result=${it?.bookName ?: "null(无记忆行)"}")
        }

    suspend fun getByBookUrl(bookUrl: String): ReadingMemory? = dao.getByBookUrlSync(bookUrl)

    suspend fun getBook(bookUrl: String): Book? = bookDao.getBook(bookUrl)

    // endregion

    // region 确保记忆存在 / 同步

    /**
     * 确保 bookUrl 在 readingMemory 表中有一行。
     * 若不存在则插入最小 stub（bookUrl 为主键），若已存在则跳过。
     */
    /**
     * 确保某本书的记忆行存在并填充真实数据（自动汇总 Book / ReadRecord / 含笔记书签，幂等）。
     * 若书籍已不在库则返回最小 stub。
     */
    suspend fun ensureMemory(bookUrl: String): ReadingMemory {
        val book = bookDao.getBook(bookUrl) ?: return ReadingMemory.defaultStub(bookUrl)
        generateMemory(book)
        return dao.getByBookUrlSync(bookUrl) ?: ReadingMemory.defaultStub(bookUrl)
    }

    /**
     * 从 Book 实体同步基础信息到阅读记忆（不覆盖用户编辑字段）。
     * 通常在书籍信息刷新（如封面/简介/书名变化）时调用。
     */
    suspend fun syncFromBook(book: Book) {
        val memory = dao.getByBookUrlSync(book.bookUrl) ?: return
        val now = System.currentTimeMillis()
        dao.syncFromBook(
            bookUrl = book.bookUrl,
            bookName = book.name,
            bookAuthor = book.author,
            coverUrl = book.coverUrl,
            intro = book.intro,
            kind = book.kind,
            customTag = book.customTag,
            wordCount = book.wordCount,
            type = book.type,
            progress = memory.progress,
            totalChapterNum = book.totalChapterNum,
            durChapterIndex = book.durChapterIndex,
            durChapterPos = book.durChapterPos,
            lastReadTime = book.lastCheckTime.takeIf { it > 0 } ?: memory.lastReadTime,
            updateTime = now,
        )
    }

    // endregion

    // region 用户可编辑字段

    suspend fun markAbandoned(bookUrl: String) {
        dao.markAbandoned(bookUrl)
    }

    suspend fun unmarkAbandoned(bookUrl: String) {
        dao.unmarkAbandoned(bookUrl)
    }

    suspend fun updateRating(bookUrl: String, rating: Float) {
        dao.updateRating(bookUrl, rating)
    }

    suspend fun updateReview(bookUrl: String, review: String?) {
        dao.updateReview(bookUrl, review)
    }

    /** 删除书评（将书评置空）。 */
    suspend fun deleteReview(bookUrl: String) {
        dao.updateReview(bookUrl, null)
    }

    suspend fun updateProgress(
        bookUrl: String,
        durChapterIndex: Int,
        durChapterPos: Int,
        progress: Float,
        totalChapterNum: Int,
    ) {
        dao.updateProgress(
            bookUrl = bookUrl,
            progress = progress,
            durChapterIndex = durChapterIndex,
            durChapterPos = durChapterPos,
            lastReadTime = System.currentTimeMillis(),
            totalChapterNum = totalChapterNum,
            updateTime = System.currentTimeMillis(),
        )
    }

    // endregion

    // region 删除前快照

    /**
     * 书架删除书籍前调用：将当前书籍信息快照到 readingMemory。
     * 快照后记忆不会被删除，保留阅读历史。
     */
    suspend fun snapshotOnDelete(book: Book) {
        // 先确保真实数据已汇总到记忆行（含进度/统计/书摘数）
        generateMemory(book)
        val memory = dao.getByBookUrlSync(book.bookUrl) ?: return

        // 提取主角信息做 JSON 快照
        val protagonists = bookKnowledgeDao.getProtagonists(book.bookUrl)
        val protagonistsJson = if (protagonists.isNotEmpty()) {
            protagonists.joinToString("|") { it.name }
        } else {
            memory.protagonistsJson
        }

        // 计算阅读统计
        val stats = computeStatistics(book)
        val annotationCount = bookmarkDao.countWithNote(book.name, book.author)
        val progress = computeProgress(book)
        val lastReadTime = if (book.durChapterTime > 0) book.durChapterTime else book.lastCheckTime

        val now = System.currentTimeMillis()
        dao.snapshotOnDelete(
            bookUrl = book.bookUrl,
            kind = book.kind,
            customTag = book.customTag,
            protagonistsJson = protagonistsJson,
            excerptsJson = memory.excerptsJson,
            statTotalReadTime = stats.totalReadTime,
            statReadingDays = stats.readingDays,
            statMaxDayReadTime = stats.maxDayReadTime,
            statMaxDayReadDate = stats.maxDayReadDate,
            statTotalWords = stats.totalWords,
            annotationCount = annotationCount,
            progress = progress,
            totalChapterNum = book.totalChapterNum,
            durChapterIndex = book.durChapterIndex,
            durChapterPos = book.durChapterPos,
            lastReadTime = lastReadTime,
            updateTime = now,
        )
    }

    // endregion

    // region 批量操作

    /**
     * 批量汇总生成全部书架书籍的阅读记忆（自动、幂等）。
     * 列表/详情打开时调用，保证「打开即有数据」。
     */
    suspend fun ensureAllMemories() {
        val books = bookDao.all
        for (book in books) {
            generateMemory(book)
        }
    }

    /**
     * 从实时数据源汇总生成单本书的阅读记忆，保留用户编辑字段（评分/书评/弃文/手动简介）。
     */
    private suspend fun generateMemory(book: Book) {
        val existing = dao.getByBookUrlSync(book.bookUrl)
        val base = existing ?: ReadingMemory(bookUrl = book.bookUrl)
        val stats = computeStatistics(book)
        val annotationCount = bookmarkDao.countWithNote(book.name, book.author)
        val now = System.currentTimeMillis()
        val progress = computeProgress(book)
        val lastReadTime = if (book.durChapterTime > 0) book.durChapterTime else book.lastCheckTime
        val firstReadTime = if (existing != null && existing.firstReadTime > 0) {
            existing.firstReadTime
        } else {
            deriveFirstReadTime(book)
        }
        val finishReadTime = if (progress >= 1f) {
            existing?.finishReadTime?.takeIf { it > 0 } ?: lastReadTime
        } else {
            existing?.finishReadTime ?: 0L
        }
        val memory = base.copy(
            bookUrl = book.bookUrl,
            bookName = book.name,
            bookAuthor = book.author,
            coverUrl = book.getDisplayCover(),
            intro = if (base.userModifiedIntro) base.intro else book.getDisplayIntro(),
            userModifiedIntro = base.userModifiedIntro,
            kind = book.kind,
            customTag = book.customTag,
            wordCount = book.wordCount,
            type = book.type,
            progress = progress,
            totalChapterNum = book.totalChapterNum,
            durChapterIndex = book.durChapterIndex,
            durChapterPos = book.durChapterPos,
            rating = base.rating,
            review = base.review,
            abandoned = base.abandoned,
            firstReadTime = firstReadTime,
            finishReadTime = finishReadTime,
            lastReadTime = lastReadTime,
            createTime = if (base.createTime > 0) base.createTime else now,
            updateTime = now,
            annotationCount = annotationCount,
            protagonistsJson = base.protagonistsJson,
            excerptsJson = base.excerptsJson,
            statTotalReadTime = stats.totalReadTime,
            statReadingDays = stats.readingDays,
            statMaxDayReadTime = stats.maxDayReadTime,
            statMaxDayReadDate = stats.maxDayReadDate,
            statTotalWords = stats.totalWords,
        )
        dao.upsert(memory)
    }

    /** 由当前章节索引与总章节数估算阅读进度 (0f..1f) */
    private fun computeProgress(book: Book): Float {
        if (book.totalChapterNum <= 0) return 0f
        if (book.durChapterIndex <= 0) return 0f
        if (book.totalChapterNum == 1) return 1f
        return (book.durChapterIndex.toFloat() / (book.totalChapterNum - 1)).coerceIn(0f, 1f)
    }

    /** 首次阅读时间：取阅读记录中最早一天；无记录则回落到最近阅读时间 */
    private suspend fun deriveFirstReadTime(book: Book): Long {
        val details = readRecordDao.getDetailsByBook(DEVICE_ID, book.name, book.author)
        val minDate = details.minOfOrNull { it.date }
        if (!minDate.isNullOrBlank()) {
            try {
                val parts = minDate.split("-")
                if (parts.size == 3) {
                    val cal = Calendar.getInstance()
                    cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    return cal.timeInMillis
                }
            } catch (_: Exception) {
            }
        }
        return book.durChapterTime.takeIf { it > 0 } ?: 0L
    }

    suspend fun syncFromBook(bookUrl: String) {
        val book = bookDao.getBook(bookUrl) ?: return
        syncFromBook(book)
    }

    suspend fun computeStatistics(bookUrl: String): ReadingStatistics {
        val book = bookDao.getBook(bookUrl)
        AppLog.put("[阅读记忆] computeStatistics bookUrl=$bookUrl book=${book?.name ?: "null(书籍不在库)"}")
        if (book == null) return ReadingStatistics(0L, 0, 0L, null, 0L)
        return computeStatistics(book)
    }

    suspend fun updateIntro(bookUrl: String, intro: String?) {
        dao.updateIntro(bookUrl, intro)
    }

    suspend fun snapshotOnDelete(bookUrl: String) {
        val book = bookDao.getBook(bookUrl) ?: return
        snapshotOnDelete(book)
    }

    // endregion

    // region 统计

    /**
     * 计算指定书籍的阅读统计信息。
     */
    suspend fun computeStatistics(book: Book): ReadingStatistics {
        val readRecord = readRecordDao.getReadRecord(DEVICE_ID, book.name, book.author)
        if (readRecord == null) {
            return ReadingStatistics(
                totalReadTime = 0L,
                readingDays = 0,
                maxDayReadTime = 0L,
                maxDayReadDate = null,
                totalWords = 0L,
            )
        }

        val totalReadTime = readRecord.readTime
        val details = readRecordDao.getDetailsByBook(DEVICE_ID, book.name, book.author)

        val readingDays = details.size
        val maxDayDetail = details.maxByOrNull { it.readTime }
        val maxDayReadTime = maxDayDetail?.readTime ?: 0L
        val maxDayReadDate = maxDayDetail?.date
        val totalWords = details.sumOf { it.readWords }

        return ReadingStatistics(
            totalReadTime = totalReadTime,
            readingDays = readingDays,
            maxDayReadTime = maxDayReadTime,
            maxDayReadDate = maxDayReadDate,
            totalWords = totalWords,
        )
    }

    // endregion

    // region 书摘 / 阅读会话

    /**
     * 书摘 = 含笔记的书签（content 非空），按章节顺序返回。
     * 计划定义：书摘就用「有笔记的书签」。
     */
    suspend fun getExcerpts(bookUrl: String): List<Bookmark> {
        val book = bookDao.getBook(bookUrl) ?: return emptyList()
        return bookmarkDao.getByBook(book.name, book.author)
            .filter { !it.content.isNullOrBlank() }
            .sortedBy { it.chapterIndex }
    }

    /** 保存（新增或更新）书签，供阅读摘录编辑复用「书签对话框」。 */
    suspend fun saveBookmark(bookmark: Bookmark) {
        bookmarkDao.insert(bookmark)
    }

    /** 删除书签。 */
    suspend fun deleteBookmark(bookmark: Bookmark) {
        bookmarkDao.delete(bookmark)
    }

    // region 阅读记录（时间线 / 复用「阅读记录对话框」内容）

    /**
     * 阅读记录时间线（按天的会话分组），复用于「阅读记录对话框 / 阅读会话卡片」。
     */
    suspend fun getReadRecordTimelineDays(bookUrl: String): List<ReadRecordTimelineDay> {
        val book = bookDao.getBook(bookUrl) ?: return emptyList()
        return readRecordRepository.getBookTimelineDays(book.name, book.author).first()
    }

    /**
     * 本书累计阅读时长（毫秒），复用于「阅读记录对话框 / 阅读会话卡片」。
     */
    suspend fun getReadRecordTotalTime(bookUrl: String): Long {
        val book = bookDao.getBook(bookUrl) ?: return 0L
        return readRecordRepository.getBookReadTime(book.name, book.author).first()
    }

    // endregion

    // region 标签（复用于书架彩色标签选择对话框）

    /**
     * 书架上已有的全部标签名，供阅读记忆「标签选择」对话框复用（参考书架标签）。
     */
    suspend fun getAvailableTags(): List<String> =
        database.bookTagDao.getAllSync().map { it.name }.filter { it.isNotBlank() }

    fun observeTagColorMap(): Flow<Map<String, Long>> =
        database.bookTagDao.observeAll().map { tags ->
            tags.filter { !it.name.isNullOrBlank() && it.color != 0L }
                .associate { it.name to it.color }
        }

    /** 书架标签分组列表，供阅读记忆「标签选择」对话框按分组展示。 */
    suspend fun getTagGroups(): List<BookTagGroup> =
        database.bookTagGroupDao.getAllSorted()

    /** 当前排除规则列表，供按排除规则过滤书籍标签。 */
    suspend fun getExcludedTags(): List<ExcludedTag> =
        database.excludedTagDao.getAllSync()

    private fun tagsFromKind(kind: String): MutableSet<String> =
        kind.split(",", "|").map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()

    private suspend fun applyTags(bookUrl: String, book: Book?, set: MutableSet<String>) {
        val kind = set.joinToString("|")
        if (book != null) {
            book.kind = kind
            bookDao.update(book)
            // 同步关系表：以 kind 为 SSOT 重建该书标签关联，保证标签管理页计数一致
            database.bookTagRelationDao.deleteByBookUrl(bookUrl)
            TagManager.generateTagsFromKind(book)
        } else {
            val memory = dao.getByBookUrlSync(bookUrl) ?: return
            dao.updateKind(bookUrl, kind)
        }
    }

    /** 新增标签：写入书籍的 kind 字段（在架时）或记忆快照的 kind（已删除时）。 */
    suspend fun addTag(bookUrl: String, tag: String) {
        val t = tag.trim()
        if (t.isBlank()) return
        val book = bookDao.getBook(bookUrl)
        val set = tagsFromKind(book?.kind ?: dao.getByBookUrlSync(bookUrl)?.kind.orEmpty())
        if (set.add(t)) applyTags(bookUrl, book, set)
    }

    /** 移除标签。 */
    suspend fun removeTag(bookUrl: String, tag: String) {
        val t = tag.trim()
        if (t.isBlank()) return
        val book = bookDao.getBook(bookUrl)
        val set = tagsFromKind(book?.kind ?: dao.getByBookUrlSync(bookUrl)?.kind.orEmpty())
        if (set.remove(t)) applyTags(bookUrl, book, set)
    }

    // endregion

    // region 主角提取

    /**
     * 从书籍简介中提取主角名并标记为主角。
     * 提取成功则将主角名写入 book_character_profiles 并设置 isProtagonist = true。
     * 返回提取到的主角名列表。
     */
    suspend fun extractProtagonists(bookUrl: String, intro: String): List<String> {
        val names = ProtagonistExtractor.extract(intro)
        if (names.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        names.forEach { name ->
            val existing = bookKnowledgeDao.getProtagonistByName(bookUrl, name)
            if (existing == null) {
                // 创建新的角色条目并标记为主角
                val profile = BookCharacterProfile(
                    id = UUID.randomUUID().toString(),
                    bookUrl = bookUrl,
                    name = name,
                    source = BookCharacterProfile.SOURCE_AI,
                    isProtagonist = true,
                    createdAt = now,
                    updatedAt = now,
                )
                bookKnowledgeDao.upsertCharacterProfile(profile)
            } else if (!existing.isProtagonist) {
                // 已有角色但未标记为主角，更新标记
                bookKnowledgeDao.setProtagonist(bookUrl, name, true, now)
            }
        }
        return names
    }

    /**
     * 打开书籍时自动从简介提取主角（仅在架书籍才提取）。
     */
    suspend fun autoExtractProtagonists(bookUrl: String) {
        val book = bookDao.getBook(bookUrl) ?: return
        val intro = book.getDisplayIntro()
        if (!intro.isNullOrBlank()) {
            extractProtagonists(bookUrl, intro)
        }
    }

    /**
     * 主角名列表：在架书籍取 [BookCharacterProfile] 实时表，已删除书籍回落 memory 快照。
     */
    suspend fun getProtagonistNames(bookUrl: String): List<String> {
        return if (bookDao.getBook(bookUrl) != null) {
            bookKnowledgeDao.getProtagonists(bookUrl).map { it.name }
        } else {
            getByBookUrl(bookUrl)?.protagonistsJson
                ?.split("|")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: emptyList()
        }
    }

    /**
     * 设置/取消某角色的主角标记。
     */
    suspend fun setProtagonist(bookUrl: String, name: String, isProtagonist: Boolean) {
        bookKnowledgeDao.setProtagonist(name, bookUrl, isProtagonist, System.currentTimeMillis())
    }

    // endregion

    // region 删除操作

    /**
     * 删除指定书籍的阅读记忆。
     */
    suspend fun deleteMemory(bookUrl: String) {
        dao.deleteByBookUrl(bookUrl)
    }

    /**
     * 换源时保留全部阅读记忆：将旧 bookUrl 的记录完整迁移到新 bookUrl。
     * 用户评分/书评/弃文标记/主角/书摘/阅读统计等全部字段均保留。
     * 调用前应确保新 bookUrl 已有 stub 或已调用 ensureMemory。
     */
    suspend fun migrateMemory(oldBookUrl: String, newBookUrl: String) {
        if (oldBookUrl == newBookUrl) return
        dao.migrateToNewBookUrl(oldBookUrl, newBookUrl)
        dao.deleteMigrated(oldBookUrl)
    }

    /**
     * 清空全部阅读记忆。
     */
    suspend fun clearAll() {
        dao.deleteAll()
    }

    // endregion
}

/**
 * 书籍阅读统计数据
 */
data class ReadingStatistics(
    val totalReadTime: Long,
    val readingDays: Int,
    val maxDayReadTime: Long,
    val maxDayReadDate: String?,
    val totalWords: Long,
)
