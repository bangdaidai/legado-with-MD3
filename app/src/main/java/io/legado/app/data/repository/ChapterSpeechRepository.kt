package io.legado.app.data.repository

import io.legado.app.data.dao.ChapterSpeechDao
import io.legado.app.data.entities.ChapterSpeechAnalysisEntity
import io.legado.app.data.entities.ChapterSpeechSegmentEntity
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysis
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
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

    override suspend fun getStoredAnalysis(
        bookUrl: String,
        chapterIndex: Int,
    ): ChapterSpeechAnalysis? = withContext(Dispatchers.IO) {
        dao.getStoredAnalysis(bookUrl, chapterIndex)?.toDomain()
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

    /**
     * 这一章存下来的那一份分段。
     *
     * 不再按 `resolverVersion` 挑「当前设置那一份」：划分方式 / 分析模式 / 模型 / 提示词 / 归因口径
     * 一改就退回旧行、再把界面标成「不作数」，等于用户改一次设置把做好的分镜作废。库里那一行就是
     * 这一章的结果，界面与朗读读的都是它（同 [getStoredAnalysis]）。
     */
    override suspend fun getChapterSegments(
        bookUrl: String,
        chapterIndex: Int,
    ): List<ChapterSpeechSegment> = withContext(Dispatchers.IO) {
        dao.getChapterSegments(bookUrl, chapterIndex).map(ChapterSpeechSegmentEntity::toDomain)
    }

    override suspend fun getChapterSummaries(
        bookUrl: String,
    ): List<ChapterSpeechSummary> = withContext(Dispatchers.IO) {
        // 每章只统计 getStoredAnalysis 会拿到的那一行，挑法写在 DAO 里，
        // 列表上的段数/人数与点进去看到的分段因此同源。
        dao.getChapterSummaries(bookUrl).map { row ->
            ChapterSpeechSummary(
                chapterIndex = row.chapterIndex,
                title = row.title,
                segmentCount = row.segmentCount,
                characterCount = row.characterCount,
                updatedAt = row.updatedAt,
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

    /**
     * 定稿/放开某一段的归属（口径见
     * [io.legado.app.domain.gateway.ChapterSpeechGateway.setSegmentAttribution]）。
     *
     * `source` 一律写 user：无论「这句归谁」还是「取消这句的归属」，都是用户当场做的决定，
     * 分镜页那一栏显示「用户」是对的。真正的保护靠 `userLocked`——重跑分析跳过锁定段，
     * 与 source 无关。
     */
    override suspend fun setSegmentAttribution(
        segmentId: String,
        roleType: SpeechRoleType,
        characterId: String?,
        characterName: String,
        userLocked: Boolean,
    ) = withContext(Dispatchers.IO) {
        dao.setAttribution(
            id = segmentId,
            roleType = roleType.storageValue,
            characterId = characterId,
            characterName = characterName,
            source = SpeechResolutionSource.User.storageValue,
            locked = userLocked,
            now = System.currentTimeMillis(),
        )
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
