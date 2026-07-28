package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.legado.app.data.entities.ReadingMemory
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingMemoryDao {

    /** 按 bookUrl 查询（Flow） */
    @Query("SELECT * FROM readingMemory WHERE bookUrl = :bookUrl LIMIT 1")
    fun getByBookUrl(bookUrl: String): Flow<ReadingMemory?>

    /** 按 bookUrl 查询（同步，供 Repository 内部使用） */
    @Query("SELECT * FROM readingMemory WHERE bookUrl = :bookUrl LIMIT 1")
    suspend fun getByBookUrlSync(bookUrl: String): ReadingMemory?

    /** 全部（Flow） */
    @Query("SELECT * FROM readingMemory ORDER BY updateTime DESC")
    fun getAll(): Flow<List<ReadingMemory>>

    /** 插入或替换 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(memory: ReadingMemory)

    /** 批量插入或替换（供备份恢复使用） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ReadingMemory>)

    /** 插入（冲突时忽略，不覆盖已有记录） */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(memory: ReadingMemory)

    /** 标记弃文 */
    @Query("UPDATE readingMemory SET abandoned = 1, updateTime = :updateTime WHERE bookUrl = :bookUrl")
    suspend fun markAbandoned(bookUrl: String, updateTime: Long = System.currentTimeMillis())

    /** 取消弃文标记 */
    @Query("UPDATE readingMemory SET abandoned = 0, updateTime = :updateTime WHERE bookUrl = :bookUrl")
    suspend fun unmarkAbandoned(bookUrl: String, updateTime: Long = System.currentTimeMillis())

    /** 删除前快照：将实时派生数据写入 memory */
    @Query(
        """
        UPDATE readingMemory SET
            protagonistsJson = :protagonistsJson,
            excerptsJson = :excerptsJson,
            statTotalReadTime = :statTotalReadTime,
            statReadingDays = :statReadingDays,
            statMaxDayReadTime = :statMaxDayReadTime,
            statMaxDayReadDate = :statMaxDayReadDate,
            statTotalWords = :statTotalWords,
            annotationCount = :annotationCount,
            progress = :progress,
            totalChapterNum = :totalChapterNum,
            durChapterIndex = :durChapterIndex,
            durChapterPos = :durChapterPos,
            lastReadTime = :lastReadTime,
            updateTime = :updateTime
        WHERE bookUrl = :bookUrl
        """
    )
    suspend fun snapshotOnDelete(
        bookUrl: String,
        protagonistsJson: String?,
        excerptsJson: String?,
        statTotalReadTime: Long,
        statReadingDays: Int,
        statMaxDayReadTime: Long,
        statMaxDayReadDate: String?,
        statTotalWords: Long,
        annotationCount: Int,
        progress: Float,
        totalChapterNum: Int,
        durChapterIndex: Int,
        durChapterPos: Int,
        lastReadTime: Long,
        updateTime: Long = System.currentTimeMillis(),
    )

    /** 从 Book 实体同步元数据（不覆盖用户修改的 rating/review/abandoned/userModifiedIntro） */
    @Query(
        """
        UPDATE readingMemory SET
            bookName = :bookName,
            bookAuthor = :bookAuthor,
            coverUrl = :coverUrl,
            intro = CASE WHEN userModifiedIntro = 0 THEN :intro ELSE intro END,
            kind = :kind,
            wordCount = :wordCount,
            type = :type,
            progress = :progress,
            totalChapterNum = :totalChapterNum,
            durChapterIndex = :durChapterIndex,
            durChapterPos = :durChapterPos,
            lastReadTime = :lastReadTime,
            updateTime = :updateTime
        WHERE bookUrl = :bookUrl
        """
    )
    suspend fun syncFromBook(
        bookUrl: String,
        bookName: String,
        bookAuthor: String,
        coverUrl: String?,
        intro: String?,
        kind: String?,
        wordCount: String?,
        type: Int,
        progress: Float,
        totalChapterNum: Int,
        durChapterIndex: Int,
        durChapterPos: Int,
        lastReadTime: Long,
        updateTime: Long = System.currentTimeMillis(),
    )

    /** 更新评分 */
    @Query("UPDATE readingMemory SET rating = :rating, updateTime = :updateTime WHERE bookUrl = :bookUrl")
    suspend fun updateRating(
        bookUrl: String,
        rating: Float,
        updateTime: Long = System.currentTimeMillis(),
    )

    /** 更新书评 */
    @Query("UPDATE readingMemory SET review = :review, updateTime = :updateTime WHERE bookUrl = :bookUrl")
    suspend fun updateReview(
        bookUrl: String,
        review: String?,
        updateTime: Long = System.currentTimeMillis(),
    )

    /** 更新阅读进度 */
    @Query("UPDATE readingMemory SET progress = :progress, durChapterIndex = :durChapterIndex, durChapterPos = :durChapterPos, lastReadTime = :lastReadTime, totalChapterNum = :totalChapterNum, updateTime = :updateTime WHERE bookUrl = :bookUrl")
    suspend fun updateProgress(
        bookUrl: String,
        progress: Float,
        durChapterIndex: Int,
        durChapterPos: Int,
        lastReadTime: Long,
        totalChapterNum: Int,
        updateTime: Long = System.currentTimeMillis(),
    )

    /** 按 bookUrl 删除（保留，但书架删除时不应调此方法，记忆独立保留） */
    @Query("DELETE FROM readingMemory WHERE bookUrl = :bookUrl")
    suspend fun deleteByBookUrl(bookUrl: String)

    /** 清空全部阅读记忆 */
    @Query("DELETE FROM readingMemory")
    suspend fun deleteAll()

    /** 确保存在（空操作；外部调用 upsert 即可） */
    @Query("SELECT COUNT(*) FROM readingMemory WHERE bookUrl = :bookUrl")
    suspend fun exists(bookUrl: String): Int

    /** 更新用户修改的简介 */
    @Query("UPDATE readingMemory SET intro = :intro, userModifiedIntro = 1, updateTime = :updateTime WHERE bookUrl = :bookUrl")
    suspend fun updateIntro(
        bookUrl: String,
        intro: String?,
        updateTime: Long = System.currentTimeMillis(),
    )

    /** 更新标签（kind，以 "|" 分隔） */
    @Query("UPDATE readingMemory SET kind = :kind, updateTime = :updateTime WHERE bookUrl = :bookUrl")
    suspend fun updateKind(
        bookUrl: String,
        kind: String,
        updateTime: Long = System.currentTimeMillis(),
    )

    /** 全量查询（供备份使用，非 Flow） */
    @Query("SELECT * FROM readingMemory")
    suspend fun getAllSync(): List<ReadingMemory>

    /** 换源：将阅读记忆从旧 bookUrl 迁移到新 bookUrl，保留全部用户编辑数据 */
    @Query(
        """
        INSERT OR REPLACE INTO readingMemory(bookUrl, bookName, bookAuthor, coverUrl, intro, userModifiedIntro,
            kind, wordCount, type, progress, totalChapterNum, durChapterIndex, durChapterPos,
            rating, review, abandoned, firstReadTime, finishReadTime, lastReadTime,
            createTime, updateTime, annotationCount,
            protagonistsJson, excerptsJson,
            statTotalReadTime, statReadingDays, statMaxDayReadTime, statMaxDayReadDate, statTotalWords)
        SELECT
            :newBookUrl,
            bookName, bookAuthor, coverUrl, intro, userModifiedIntro,
            kind, wordCount, type, progress, totalChapterNum, durChapterIndex, durChapterPos,
            rating, review, abandoned, firstReadTime, finishReadTime, lastReadTime,
            createTime, :now,
            annotationCount,
            protagonistsJson, excerptsJson,
            statTotalReadTime, statReadingDays, statMaxDayReadTime, statMaxDayReadDate, statTotalWords
        FROM readingMemory WHERE bookUrl = :oldBookUrl
        """
    )
    suspend fun migrateToNewBookUrl(oldBookUrl: String, newBookUrl: String, now: Long = System.currentTimeMillis())

    /** 清理换源后的旧记录 */
    @Query("DELETE FROM readingMemory WHERE bookUrl = :bookUrl")
    suspend fun deleteMigrated(bookUrl: String)
}
