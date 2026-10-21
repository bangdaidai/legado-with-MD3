package io.legado.app.data.repository

import io.legado.app.data.dao.ChapterSpeechDao
import io.legado.app.data.entities.ChapterSpeechAnalysisEntity
import io.legado.app.data.entities.ChapterSpeechSegmentEntity
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysis
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.ChapterSpeechSegments
import io.legado.app.domain.model.readaloud.ChapterSpeechSummary
import io.legado.app.domain.model.readaloud.SpeechAnalysisStatus
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChapterSpeechRepository(
    private val dao: ChapterSpeechDao,
) : ChapterSpeechGateway {

    override suspend fun getAnalysis(
        bookUrl: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): ChapterSpeechAnalysis? = withContext(Dispatchers.IO) {
        dao.getAnalysis(bookUrl, chapterIndex, contentHash, resolverVersion)?.toDomain()
    }

    override suspend fun upsertAnalysis(analysis: ChapterSpeechAnalysis) =
        withContext(Dispatchers.IO) {
            dao.upsertAnalysis(analysis.toEntity())
        }

    override suspend fun saveAnalysis(
        analysis: ChapterSpeechAnalysis,
        segments: List<ChapterSpeechSegment>,
    ) = withContext(Dispatchers.IO) {
        require(segments.all { it.analysisId == analysis.id }) {
            "All speech segments must belong to analysis ${analysis.id}"
        }
        dao.saveAnalysis(analysis.toEntity(), segments.map(ChapterSpeechSegment::toEntity))
    }

    override suspend fun getSegments(analysisId: String): List<ChapterSpeechSegment> =
        withContext(Dispatchers.IO) {
            dao.getSegments(analysisId).map(ChapterSpeechSegmentEntity::toDomain)
        }

    override suspend fun getChapterSegments(
        bookUrl: String,
        chapterIndex: Int,
    ): List<ChapterSpeechSegment> = withContext(Dispatchers.IO) {
        dao.getChapterSegments(bookUrl, chapterIndex).map(ChapterSpeechSegmentEntity::toDomain)
    }

    /**
     * 取当前设置会用的那一份，匹配不上才退回最近一次并标 [ChapterSpeechSegments.isCurrent] = false。
     *
     * 同一章可以同时存在多份 analysis（划分方式、分析模式、模型或提示词一变 resolverVersion 就变；人物档案不在版本号里，见 RefineSpeechWithAiUseCase.resolverVersion），
     * 按 updatedAt 取最新拿到的不一定是正在播的那份——命中缓存时写入方不会更新 updatedAt，
     * 于是"先用 AI 模式读过、后切回规则模式"的章节会一直返回旧划分。界面按那份分段显示的
     * 角色与音色，跟实际播出的完全是两套。
     */
    override suspend fun getChapterSegmentsFor(
        bookUrl: String,
        chapterIndex: Int,
        resolverVersion: String,
    ): ChapterSpeechSegments = withContext(Dispatchers.IO) {
        val exact = dao.getAnalysisByResolverVersion(bookUrl, chapterIndex, resolverVersion)
        if (exact != null) {
            ChapterSpeechSegments(
                segments = dao.getSegments(exact.id).map(ChapterSpeechSegmentEntity::toDomain),
                isCurrent = true,
                // 当前设置那一份才有可对比的正文基准；退回最近一次时给空串，
                // 界面据此判「无法确认同源」，不拿覆盖层去套一份坐标可能对不上的分段
                contentHash = exact.contentHash,
            )
        } else {
            ChapterSpeechSegments(
                segments = dao.getChapterSegments(bookUrl, chapterIndex)
                    .map(ChapterSpeechSegmentEntity::toDomain),
                isCurrent = false,
            )
        }
    }

    override suspend fun getChapterSummaries(
        bookUrl: String,
        resolverVersion: String,
    ): List<ChapterSpeechSummary> = withContext(Dispatchers.IO) {
        /*
         * 每章先挑「版本号等于当前设置」的那一行，没有才退回最近一次——与
         * [getChapterSegmentsFor] 的 exact 命中同一个口径，徽标与详情页不会各说各话。
         *
         * 旧判据是「本章 max(updatedAt) 那一行等不等于当前版本」。可"最新落库"不等于"现在在用"：
         * 命中缓存时写入方不刷 updatedAt，所以先用 AI 读过、再切回规则重读的章节，最新那行还是
         * AI 那份，明明在用的当前结果反倒被标成"旧划分"。
         */
        val chosen = dao.getChapterCurrentAnalyses(bookUrl, resolverVersion)
            .associate { it.chapterIndex to it.resolverVersion }
        dao.getChapterSummaries(bookUrl, resolverVersion).map { row ->
            ChapterSpeechSummary(
                chapterIndex = row.chapterIndex,
                title = row.title,
                segmentCount = row.segmentCount,
                characterCount = row.characterCount,
                updatedAt = row.updatedAt,
                isCurrent = chosen[row.chapterIndex] == resolverVersion,
            )
        }
    }


    override suspend fun replaceSegments(
        analysisId: String,
        segments: List<ChapterSpeechSegment>,
    ) = withContext(Dispatchers.IO) {
        require(segments.all { it.analysisId == analysisId }) {
            "All speech segments must belong to analysis $analysisId"
        }
        dao.replaceSegments(analysisId, segments.map(ChapterSpeechSegment::toEntity))
    }

    /**
     * 只改这一行的 `userLocked`，不碰其它列（口径见
     * [io.legado.app.domain.gateway.ChapterSpeechGateway.setSegmentUserLocked]）。
     */
    override suspend fun setSegmentUserLocked(segmentId: String, locked: Boolean) =
        withContext(Dispatchers.IO) {
            dao.setUserLocked(segmentId, locked, System.currentTimeMillis())
        }

    override suspend fun deleteChapter(bookUrl: String, chapterIndex: Int) =
        withContext(Dispatchers.IO) {
            dao.deleteChapter(bookUrl, chapterIndex)
        }

    override suspend fun deleteBook(bookUrl: String) = withContext(Dispatchers.IO) {
        dao.delByBook(bookUrl)
    }
}

private fun ChapterSpeechAnalysisEntity.toDomain() = ChapterSpeechAnalysis(
    id = id,
    bookUrl = bookUrl,
    chapterIndex = chapterIndex,
    contentHash = contentHash,
    resolverVersion = resolverVersion,
    characterRevision = characterRevision,
    status = SpeechAnalysisStatus.fromStorage(status),
    error = error,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun ChapterSpeechAnalysis.toEntity() = ChapterSpeechAnalysisEntity(
    id = id,
    bookUrl = bookUrl,
    chapterIndex = chapterIndex,
    contentHash = contentHash,
    resolverVersion = resolverVersion,
    characterRevision = characterRevision,
    status = status.storageValue,
    error = error,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun ChapterSpeechSegmentEntity.toDomain() = ChapterSpeechSegment(
    id = id,
    analysisId = analysisId,
    bookUrl = bookUrl,
    chapterIndex = chapterIndex,
    paragraphIndex = paragraphIndex,
    start = start,
    end = end,
    chapterPosition = chapterPosition,
    text = text,
    roleType = SpeechRoleType.fromStorage(roleType),
    characterId = characterId,
    characterName = characterName,
    emotion = emotion,
    confidence = confidence,
    source = SpeechResolutionSource.fromStorage(source),
    userLocked = userLocked,
    sceneIndex = sceneIndex,
    sceneTitle = sceneTitle,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun ChapterSpeechSegment.toEntity() = ChapterSpeechSegmentEntity(
    id = id,
    analysisId = analysisId,
    bookUrl = bookUrl,
    chapterIndex = chapterIndex,
    paragraphIndex = paragraphIndex,
    start = start,
    end = end,
    chapterPosition = chapterPosition,
    text = text,
    roleType = roleType.storageValue,
    characterId = characterId,
    characterName = characterName,
    emotion = emotion,
    confidence = confidence,
    source = source.storageValue,
    userLocked = userLocked,
    sceneIndex = sceneIndex,
    sceneTitle = sceneTitle,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
