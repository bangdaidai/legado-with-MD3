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
     * 定稿/放开某一段的归属（正文胶囊分配一句话的落点，见
     * [io.legado.app.help.readaloud.cast.CastSegmentFinalizer]）。
     *
     * 只改这一行的归属四列 + `userLocked`，不碰正文与起止点：界面上那一行可能是分配表
     * 覆盖切过之后的碎片（正文和起止点都被改过），整份回写分段会把库里那条分析段换成碎片。
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

    /**
     * 上一章正文的末尾片段，给说话人分析当背景（新章开头的代词、称呼承接要靠它指人）。
     *
     * 拿不到（第一章、上一章还没下载到本地、读正文失败）返回 null，调用方省略这一段
     * 继续分析，不许为背景缺位把正事拦下。默认 null：测试替身与暂未接线的实现不强制覆写。
     */
    suspend fun previousChapterTail(
        bookUrl: String,
        chapterIndex: Int,
        maxChars: Int,
    ): String? = null
}
