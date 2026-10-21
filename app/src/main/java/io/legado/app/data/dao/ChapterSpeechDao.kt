package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.ChapterSpeechAnalysisEntity
import io.legado.app.data.entities.ChapterSpeechSegmentEntity

/** 分镜结果按章聚合的统计行，用于章节列表 */
data class ChapterSpeechSummaryRow(
    val chapterIndex: Int,
    /** 目录里的章节标题，目录被清过时为空 */
    val title: String,
    val segmentCount: Int,
    val characterCount: Int,
    val updatedAt: Long,
)

@Dao
interface ChapterSpeechDao {

    @Query(
        "select * from chapter_speech_analysis where bookUrl = :bookUrl " +
            "and chapterIndex = :chapterIndex and contentHash = :contentHash " +
            "and resolverVersion = :resolverVersion limit 1"
    )
    suspend fun getAnalysis(
        bookUrl: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): ChapterSpeechAnalysisEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAnalysis(analysis: ChapterSpeechAnalysisEntity)

    @Query(
        "select * from chapter_speech_segments where analysisId = :analysisId " +
            "order by paragraphIndex, start, end"
    )
    suspend fun getSegments(analysisId: String): List<ChapterSpeechSegmentEntity>

    /**
     * 只取这一章**最近一次**分析的结果：换过 resolverVersion 的旧分段还留在库里，混在一起会重复。
     *
     * 注意这是"没有匹配版本时兜底看一眼"的读法，**不能拿来判"这一章是不是当前设置在用的那份"**——
     * 命中缓存时写入方不刷 `updatedAt`（见 `AnalyzeChapterSpeechUseCase` 的 fromCache 分支），
     * 最新一行未必就是当前版本那一份。判当前用 [getAnalysisByResolverVersion] /
     * [getChapterCurrentAnalyses]。
     */
    @Query(
        "select * from chapter_speech_segments where analysisId = " +
            "(select id from chapter_speech_analysis where bookUrl = :bookUrl " +
            "and chapterIndex = :chapterIndex order by updatedAt desc limit 1) " +
            "order by paragraphIndex, start, end"
    )
    suspend fun getChapterSegments(
        bookUrl: String,
        chapterIndex: Int,
    ): List<ChapterSpeechSegmentEntity>

    /** 只认当前设置会用的那一份分析。 */
    @Query(
        "select * from chapter_speech_analysis where bookUrl = :bookUrl " +
            "and chapterIndex = :chapterIndex and resolverVersion = :resolverVersion " +
            "order by updatedAt desc limit 1"
    )
    suspend fun getAnalysisByResolverVersion(
        bookUrl: String,
        chapterIndex: Int,
        resolverVersion: String,
    ): ChapterSpeechAnalysisEntity?

    /**
     * 每章「当前设置会用到的那一份」分析：先挑版本号等于 `:resolverVersion` 的行，
     * 这一章没有那种行才退回最近一次。
     *
     * 这是章节列表判「这份分析现在还作不作数」的唯一口径，与 [getAnalysisByResolverVersion]
     * 的 exact 命中同源。过去用的是「本章 `max(updatedAt)` 那一行等不等于当前版本」，
     * 而命中缓存时 `updatedAt` 根本不刷（`AnalyzeChapterSpeechUseCase` 的 fromCache 分支），
     * 于是「先用 AI 读过、再切回规则重读」的章节明明在用的是当前那份，也照样标成旧划分。
     */
    @Query(
        "select * from chapter_speech_analysis a where a.bookUrl = :bookUrl " +
            "and a.id = (select x.id from chapter_speech_analysis x " +
            "where x.bookUrl = a.bookUrl and x.chapterIndex = a.chapterIndex " +
            "order by (x.resolverVersion = :resolverVersion) desc, x.updatedAt desc limit 1)"
    )
    suspend fun getChapterCurrentAnalyses(
        bookUrl: String,
        resolverVersion: String,
    ): List<ChapterSpeechAnalysisEntity>

    /**
     * 章节列表的统计行：只统计每章 [getChapterCurrentAnalyses] 选中的那一份分析，
     * 段数/人数与「这一份现在作不作数」的徽标必须来自同一行，否则列表自相矛盾。
     */
    @Query(
        "select s.chapterIndex as chapterIndex, ifnull(c.title, '') as title, " +
            "count(*) as segmentCount, " +
            "count(distinct s.characterId) as characterCount, " +
            "max(s.updatedAt) as updatedAt from chapter_speech_segments s " +
            "left join chapters c on c.bookUrl = s.bookUrl and c.`index` = s.chapterIndex " +
            "where s.analysisId in (select a.id from chapter_speech_analysis a " +
            "where a.bookUrl = :bookUrl and a.id = " +
            "(select x.id from chapter_speech_analysis x " +
            "where x.bookUrl = a.bookUrl and x.chapterIndex = a.chapterIndex " +
            "order by (x.resolverVersion = :resolverVersion) desc, x.updatedAt desc limit 1)) " +
            "group by s.chapterIndex order by s.chapterIndex"
    )
    suspend fun getChapterSummaries(
        bookUrl: String,
        resolverVersion: String,
    ): List<ChapterSpeechSummaryRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSegments(segments: List<ChapterSpeechSegmentEntity>)

    /**
     * 只改「这一段是不是用户定稿的」这一个动作。
     *
     * 分镜页的锁定/解锁必须走行级 UPDATE，不能拿整份分段 `replaceSegments` 回写：
     * 页面上那一行可能是分配表覆盖**切过之后**的碎片（文本、起止点都被改过），
     * 整份写回会把库里那条分析段的正文和起止点换成碎片，等于改一句归属却弄坏了整章分段。
     */
    @Query(
        "update chapter_speech_segments set userLocked = :locked, updatedAt = :now where id = :id"
    )
    suspend fun setUserLocked(id: String, locked: Boolean, now: Long)

    @Query("delete from chapter_speech_segments where analysisId = :analysisId")
    suspend fun deleteSegments(analysisId: String)

    @Transaction
    suspend fun replaceSegments(
        analysisId: String,
        segments: List<ChapterSpeechSegmentEntity>,
    ) {
        deleteSegments(analysisId)
        if (segments.isNotEmpty()) upsertSegments(segments)
    }

    @Transaction
    suspend fun saveAnalysis(
        analysis: ChapterSpeechAnalysisEntity,
        segments: List<ChapterSpeechSegmentEntity>,
    ) {
        upsertAnalysis(analysis)
        replaceSegments(analysis.id, segments)
    }

    @Query("delete from chapter_speech_segments where bookUrl = :bookUrl and chapterIndex = :chapterIndex")
    suspend fun deleteChapterSegments(bookUrl: String, chapterIndex: Int)

    @Query("delete from chapter_speech_analysis where bookUrl = :bookUrl and chapterIndex = :chapterIndex")
    suspend fun deleteChapterAnalyses(bookUrl: String, chapterIndex: Int)

    @Transaction
    suspend fun deleteChapter(bookUrl: String, chapterIndex: Int) {
        deleteChapterSegments(bookUrl, chapterIndex)
        deleteChapterAnalyses(bookUrl, chapterIndex)
    }

    @Query("delete from chapter_speech_segments where bookUrl = :bookUrl")
    fun delSegmentsByBook(bookUrl: String)

    @Query("delete from chapter_speech_analysis where bookUrl = :bookUrl")
    fun delAnalysesByBook(bookUrl: String)

    /**
     * 删书时清掉整本的分镜。分镜是派生数据，书下架就没用了 —— 阅读记忆要留的是书名、作者和封面，
     * 跟这两张表无关。
     *
     * 非 suspend 是为了跟 [io.legado.app.data.entities.Book.delete] 里的
     * `bookChapterDao.delByBook` 保持同一种调用方式。
     */
    @Transaction
    fun delByBook(bookUrl: String) {
        delSegmentsByBook(bookUrl)
        delAnalysesByBook(bookUrl)
    }

    @Query("delete from chapter_speech_segments where bookUrl not in (select bookUrl from books)")
    suspend fun deleteOrphanSegments()

    @Query("delete from chapter_speech_analysis where bookUrl not in (select bookUrl from books)")
    suspend fun deleteOrphanAnalyses()

    /**
     * 兜底清理：删书路径漏掉的、或者旧版本残留的分镜数据。
     *
     * 正常路径由 [delByBook] 在删书时清掉，这里只负责收拾 bookUrl 已经不在 books 表里的行。
     * 这两张表没有指向 books 的外键，靠 CASCADE 是清不掉的。
     */
    @Transaction
    suspend fun deleteOrphans() {
        deleteOrphanSegments()
        deleteOrphanAnalyses()
    }
}
