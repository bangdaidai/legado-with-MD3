package io.legado.app.domain.gateway

import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysis
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.ChapterSpeechSummary
import io.legado.app.domain.model.readaloud.SpeechRoleType

interface ChapterSpeechGateway {
    suspend fun getAnalysis(
        bookUrl: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): ChapterSpeechAnalysis?

    /**
     * 这一章**存下来的那一份**分析：不比划分方式 / 分析模式 / 模型 / 提示词 / 归因口径。
     *
     * 它就是「这一章有没有结果」的答案。改那些设置都不会让它作废——只有没跑过的章、正文换过的章，
     * 或者用户点了「重新分析」（那里先删掉这一行）才会重跑。
     */
    suspend fun getStoredAnalysis(bookUrl: String, chapterIndex: Int): ChapterSpeechAnalysis?

    suspend fun upsertAnalysis(analysis: ChapterSpeechAnalysis)
    suspend fun saveAnalysis(
        analysis: ChapterSpeechAnalysis,
        segments: List<ChapterSpeechSegment>,
    )
    suspend fun getSegments(analysisId: String): List<ChapterSpeechSegment>

    /**
     * 某一章已存下来的分镜片段，不触发分析。
     *
     * 取的就是 [getStoredAnalysis] 那一行——朗读用的、章节列表统计的、这里拿到的同一份。
     * 这份分段量在哪一份正文上由调用方自己核对
     * （[io.legado.app.domain.model.readaloud.SpeechIdentity.alignsWithParagraphs]）。
     */
    suspend fun getChapterSegments(bookUrl: String, chapterIndex: Int): List<ChapterSpeechSegment>

    /**
     * 一本书里所有已分析过的章节统计，按章节序号升序。每章只统计 [getStoredAnalysis] 那一行，
     * 所以列表上的段数与点进去看到的分段必然同源。
     */
    suspend fun getChapterSummaries(
        bookUrl: String,
    ): List<ChapterSpeechSummary>

    suspend fun replaceSegments(analysisId: String, segments: List<ChapterSpeechSegment>)

    /**
     * 分镜页「锁定 / 解锁这一段」：只改这一行的 `userLocked`。
     *
     * 只给一列的原因见 [io.legado.app.data.dao.ChapterSpeechDao.setUserLocked]——
     * 界面上那一行可能是分配表覆盖切过的碎片，整份回写会盖掉库里那条分析段。
     * 锁定后这条归属就进了现成的「跳过锁定段」名单：AI 重跑分析不再改它。
     */
    suspend fun setSegmentUserLocked(segmentId: String, locked: Boolean)

    /**
     * 定稿/放开某一段的归属（正文胶囊分配一句话的落点，见
     * [io.legado.app.help.readaloud.cast.CastSegmentFinalizer]）。
     *
     * 只改这一行的归属四列 + `userLocked`，不碰正文与起止点，理由与
     * [setSegmentUserLocked] 相同。[characterId] 传 null、[roleType] 传
     * [SpeechRoleType.Narrator] 就是"这一段没有归属"。
     */
    suspend fun setSegmentAttribution(
        segmentId: String,
        roleType: SpeechRoleType,
        characterId: String?,
        characterName: String,
        userLocked: Boolean,
    )

    suspend fun deleteChapter(bookUrl: String, chapterIndex: Int)

    /** 删书时清掉整本的分镜 */
    suspend fun deleteBook(bookUrl: String)
}
