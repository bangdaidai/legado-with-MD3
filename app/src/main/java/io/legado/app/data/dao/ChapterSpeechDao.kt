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

    /** 只取这一章最近一次分析的结果：换过 resolverVersion 的旧分段还留在库里，混在一起会重复 */
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

    @Query(
        "select s.chapterIndex as chapterIndex, count(*) as segmentCount, " +
            "count(distinct s.characterId) as characterCount, " +
            "max(s.updatedAt) as updatedAt from chapter_speech_segments s " +
            "where s.analysisId in (select a.id from chapter_speech_analysis a " +
            "where a.bookUrl = :bookUrl and a.updatedAt = " +
            "(select max(b.updatedAt) from chapter_speech_analysis b " +
            "where b.bookUrl = a.bookUrl and b.chapterIndex = a.chapterIndex)) " +
            "group by s.chapterIndex order by s.chapterIndex"
    )
    suspend fun getChapterSummaries(bookUrl: String): List<ChapterSpeechSummaryRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSegments(segments: List<ChapterSpeechSegmentEntity>)

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
}
