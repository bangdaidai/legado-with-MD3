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
     * 这一章**存下来的那一份**分析：不比 `resolverVersion`，也不比 `contentHash`。
     *
     * 这是「这一章到底有没有结果」的唯一读法。划分方式 / 分析模式 / 模型 / 提示词 / 归因口径
     * 都写在版本号里，过去拿它一比就当没有——用户改一次设置，已经做好的分镜当场作废、再烧一次 AI。
     * 现在的口径是「没人点『重新分析』，库里那一份就是这一章的结果」，所以挑行只看时间，
     * `pending` / `running` 那种半成品除外（正文是否还同源的判定不在这里，
     * 见 [io.legado.app.domain.model.readaloud.SpeechIdentity.alignsWithParagraphs]）。
     *
     * 写入方 [saveAnalysis] 会先删掉本章其它版本那几行，一章正常情况下只剩一行；
     * 这里留着 `order by updatedAt desc limit 1` 是为了认这次改动之前留下的历史数据。
     */
    @Query(
        "select * from chapter_speech_analysis where bookUrl = :bookUrl " +
            "and chapterIndex = :chapterIndex " +
            "and status in ('success', 'partial', 'failed') " +
            "order by updatedAt desc limit 1"
    )
    suspend fun getStoredAnalysis(
        bookUrl: String,
        chapterIndex: Int,
    ): ChapterSpeechAnalysisEntity?

    /**
     * 这一章存下来的分段——与 [getStoredAnalysis] 挑的是同一行，界面与朗读不会各拿一份。
     */
    @Query(
        "select * from chapter_speech_segments where analysisId = " +
            "(select id from chapter_speech_analysis where bookUrl = :bookUrl " +
            "and chapterIndex = :chapterIndex " +
            "and status in ('success', 'partial', 'failed') " +
            "order by updatedAt desc limit 1) " +
            "order by paragraphIndex, start, end"
    )
    suspend fun getChapterSegments(
        bookUrl: String,
        chapterIndex: Int,
    ): List<ChapterSpeechSegmentEntity>

    /**
     * 章节列表的统计行：每章只统计 [getStoredAnalysis] 会拿到的那一行。
     *
     * 段数/人数与点进去看到的那一份必须来自同一行，否则列表和详情自相矛盾。过去这里多一条
     * 「版本号等于当前设置的那行优先」，是拿来算「旧划分」徽标的；徽标和那套判据一起删了。
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
            "and x.status in ('success', 'partial', 'failed') " +
            "order by x.updatedAt desc limit 1)) " +
            "group by s.chapterIndex order by s.chapterIndex"
    )
    suspend fun getChapterSummaries(
        bookUrl: String,
    ): List<ChapterSpeechSummaryRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSegments(segments: List<ChapterSpeechSegmentEntity>)

    /**
     * 定稿这一段的归属：正文胶囊分配一句话、分镜页改一段的说话人时走行级 UPDATE。
     *
     * 必须走行级 UPDATE 而不是整份 `replaceSegments` 回写——界面上那一行可能是分配覆盖
     * 切过的碎片，整份写回会把库里那条分析段的正文和起止点换成碎片。
     * 这里一次写齐「谁在说 + 是不是用户定的」：归属写进 `characterId/characterName/roleType`，
     * `source` 记成 user，`userLocked` 决定重跑分析时这一段跳不跳过
     * （现成的六处跳过判断见 `RefineSpeechWithAiUseCase`、`ResolveLocalSpeakersUseCase`、
     * `LocalCharacterSpeakerResolver`）。取消分配则传 `locked = false` 回落旁白，
     * AI 那份原判断要重新分析才回来。
     */
    @Query(
        "update chapter_speech_segments set roleType = :roleType, characterId = :characterId, " +
            "characterName = :characterName, source = :source, userLocked = :locked, " +
            "updatedAt = :now where id = :id"
    )
    suspend fun setAttribution(
        id: String,
        roleType: String,
        characterId: String?,
        characterName: String,
        source: String,
        locked: Boolean,
        now: Long,
    )

    /**
     * 角色并档：把引用了被丢掉那一行的分段改到留下来的角色上。
     *
     * 同名多池折叠删的是重复的 `cast_characters` 行，而老章分段里存的是那一行的 id
     * （AI 归因与定稿回放都写它）。不重映射就成了「查无此人」：分镜页那一行显示没人、
     * 朗读拿不到绑定音色退回旁白。只动 `characterId` 一列——名字本来就是同一个人，
     * 正文、起止点与 `userLocked` 都不碰。
     */
    @Query(
        "update chapter_speech_segments set characterId = :keeperId, updatedAt = :now " +
            "where bookUrl = :bookUrl and characterId = :droppedId"
    )
    suspend fun remapCharacterId(bookUrl: String, droppedId: String, keeperId: String, now: Long)

    /**
     * 角色改名之后同步引用他的那些分段：`characterName` 是分镜页与正文「没分配过的那几句」
     * 直接显示的那一列冗余字段（正文走
     * [io.legado.app.help.readaloud.cast.CastSegmentFinalizer.inferredLabels]，分镜页读的就是这一行），
     * 不跟着档案改，同一个人在正文里是新名、在分镜页与没分配过的句子上还是旧名。
     *
     * 只按 `characterId` 认人，**不拿旧名字去匹配**：规则与正则归因的那些段，名字是从正文里读出来的
     * 字面称呼（"他""老者"），跟档案改名没关系，按名改会把别人的话改掉。
     * 与 [remapCharacterId] 同一口径——正文、起止点与 `userLocked` 都不碰。
     */
    @Query(
        "update chapter_speech_segments set characterName = :name, updatedAt = :now " +
            "where bookUrl = :bookUrl and characterId = :characterId"
    )
    suspend fun updateCharacterName(bookUrl: String, characterId: String, name: String, now: Long)

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

    /**
     * 写入这一章的分析：**先把这一章其它版本那份旧行删掉**，再写本次的结果。
     *
     * 一章只留一份必须落在写入这一刻，不能只指望读侧挑。库里同时留着「整段」和「按符号」两份
     * 分段时，章节列表按 [getChapterSummaries] 挑中一行、朗读发声用的是另一条链写下的那一行，
     * 用户看到的就是"分镜里全是两种划分、不知道哪份算数"。读侧现在按 [getStoredAnalysis]
     * 取最近一次那份，所以这里这一刀是"一章一份"这个不变量的唯一保证。
     *
     * 同一条分析的段落本来就是整份替换（[replaceSegments] 先删后插），所以这里多加的一刀只影响
     * **其它版本号**的行：改过划分方式 / 分析模式 / 归因口径之后，这一章被点「重新分析」重跑时
     * 就把旧那份一起清掉，不再让它挂在列表里。
     */
    @Transaction
    suspend fun saveAnalysis(
        analysis: ChapterSpeechAnalysisEntity,
        segments: List<ChapterSpeechSegmentEntity>,
    ) {
        deleteChapter(analysis.bookUrl, analysis.chapterIndex)
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
