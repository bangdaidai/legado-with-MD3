package io.legado.app.domain.gateway

import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysis
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.ChapterSpeechSummary

interface ChapterSpeechGateway {
    suspend fun getAnalysis(
        bookUrl: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): ChapterSpeechAnalysis?

    suspend fun upsertAnalysis(analysis: ChapterSpeechAnalysis)
    suspend fun saveAnalysis(
        analysis: ChapterSpeechAnalysis,
        segments: List<ChapterSpeechSegment>,
    )
    suspend fun getSegments(analysisId: String): List<ChapterSpeechSegment>

    /** 某一章已存下来的分镜片段，不触发分析 */
    suspend fun getChapterSegments(bookUrl: String, chapterIndex: Int): List<ChapterSpeechSegment>

    /** 一本书里所有已分析过的章节统计，按章节序号升序 */
    suspend fun getChapterSummaries(bookUrl: String): List<ChapterSpeechSummary>

    suspend fun replaceSegments(analysisId: String, segments: List<ChapterSpeechSegment>)
    suspend fun deleteChapter(bookUrl: String, chapterIndex: Int)
}
