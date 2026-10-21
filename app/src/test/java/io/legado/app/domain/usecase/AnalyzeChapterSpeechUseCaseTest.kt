package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysis
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.ChapterSpeechSummary
import io.legado.app.domain.model.readaloud.ContentSplitPolicies
import io.legado.app.domain.model.readaloud.SpeechAnalysisStatus
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.settings.ReadAloudContentSplitMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 「默认」在 fork 口径里就是整段，段内不切角色；要检验规则切分与缓存复用得用真正会切的策略
private val roleSplitPolicy =
    ContentSplitPolicies.forMode(ReadAloudContentSplitMode.Symbols, listOf("。"))

class AnalyzeChapterSpeechUseCaseTest {

    @Test
    fun `persists stable rule segments and reuses cache`() = runBlocking {
        val gateway = MemoryChapterSpeechGateway()
        val useCase = AnalyzeChapterSpeechUseCase(gateway)
        val paragraphs = listOf(
            CanonicalSpeechParagraph(0, "旁白。“你好！”", 0),
        )

        val first = useCase("book", 3, paragraphs, policy = roleSplitPolicy, now = 100)
        val second = useCase("book", 3, paragraphs, policy = roleSplitPolicy, now = 200)

        assertFalse(first.fromCache)
        assertTrue(second.fromCache)
        assertEquals(SpeechAnalysisStatus.Partial, first.analysis.status)
        assertEquals(first.analysis.id, second.analysis.id)
        assertEquals(first.segments.map { it.id }, second.segments.map { it.id })
        assertEquals(paragraphs.single().text, first.segments.joinToString("") { it.text })
        assertEquals(1, gateway.saveCount)
    }

    @Test
    fun `keeps failed analysis so ai retry can cool down`() = runBlocking {
        val gateway = MemoryChapterSpeechGateway()
        val useCase = AnalyzeChapterSpeechUseCase(gateway)
        val paragraphs = listOf(CanonicalSpeechParagraph(0, "旁白。“你好！”", 0))

        val first = useCase("book", 3, paragraphs, now = 100)
        // AI 复核失败时记在同一条分析上，分段仍是规则结果
        gateway.saveAnalysis(
            first.analysis.copy(status = SpeechAnalysisStatus.Failed, updatedAt = 150),
            first.segments,
        )
        val second = useCase("book", 3, paragraphs, now = 200)

        // 状态和时间要原样带出去，RefineSpeechWithAiUseCase 才能判断还在冷却期
        assertTrue(second.fromCache)
        assertEquals(SpeechAnalysisStatus.Failed, second.analysis.status)
        assertEquals(150L, second.analysis.updatedAt)
        assertEquals(first.segments.map { it.id }, second.segments.map { it.id })
        assertEquals(2, gateway.saveCount)
    }

    @Test
    fun `content change invalidates segmentation cache`() = runBlocking {
        val gateway = MemoryChapterSpeechGateway()
        val useCase = AnalyzeChapterSpeechUseCase(gateway)
        val firstParagraphs = listOf(CanonicalSpeechParagraph(0, "只有旁白。", 0))
        val secondParagraphs = listOf(CanonicalSpeechParagraph(0, "旁白改变了。", 0))

        val first = useCase("book", 0, firstParagraphs, policy = roleSplitPolicy, now = 100)
        val second = useCase("book", 0, secondParagraphs, policy = roleSplitPolicy, now = 200)

        assertFalse(second.fromCache)
        assertFalse(first.analysis.id == second.analysis.id)
        assertFalse(first.segments.map { it.id } == second.segments.map { it.id })
        assertEquals(2, gateway.saveCount)
    }

    @Test
    fun `paragraph policy keeps a whole paragraph as a single narrator segment`() = runBlocking {
        val gateway = MemoryChapterSpeechGateway()
        val useCase = AnalyzeChapterSpeechUseCase(gateway)
        val paragraphs = listOf(CanonicalSpeechParagraph(0, "旁白。“你好！”", 0))

        val result = useCase(
            "book",
            0,
            paragraphs,
            policy = ContentSplitPolicies.forMode(ReadAloudContentSplitMode.Paragraph),
            now = 100,
        )

        assertEquals(1, result.segments.size)
        assertEquals(SpeechRoleType.Narrator, result.segments.single().roleType)
        assertEquals(paragraphs.single().text, result.segments.single().text)
    }
}

private class MemoryChapterSpeechGateway : ChapterSpeechGateway {
    private var analysis: ChapterSpeechAnalysis? = null
    private var segments: List<ChapterSpeechSegment> = emptyList()
    var saveCount: Int = 0

    override suspend fun getAnalysis(
        bookUrl: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): ChapterSpeechAnalysis? = analysis?.takeIf {
        it.bookUrl == bookUrl &&
            it.chapterIndex == chapterIndex &&
            it.contentHash == contentHash &&
            it.resolverVersion == resolverVersion
    }

    override suspend fun getStoredAnalysis(
        bookUrl: String,
        chapterIndex: Int,
    ): ChapterSpeechAnalysis? = analysis?.takeIf {
        it.bookUrl == bookUrl && it.chapterIndex == chapterIndex
    }

    override suspend fun upsertAnalysis(analysis: ChapterSpeechAnalysis) {
        this.analysis = analysis
    }

    override suspend fun saveAnalysis(
        analysis: ChapterSpeechAnalysis,
        segments: List<ChapterSpeechSegment>,
    ) {
        this.analysis = analysis
        this.segments = segments
        saveCount++
    }

    override suspend fun getSegments(analysisId: String): List<ChapterSpeechSegment> =
        segments.filter { it.analysisId == analysisId }

    override suspend fun replaceSegments(
        analysisId: String,
        segments: List<ChapterSpeechSegment>,
    ) {
        this.segments = segments
    }

    // 这个用例只关心"分段落库 + 缓存复用"，下面几个读取口它是路过的，给空实现即可。
    // （它们是这个接口后来加的，缺一个整个测试源集就编不过去。）
    override suspend fun getChapterSegments(
        bookUrl: String,
        chapterIndex: Int,
    ): List<ChapterSpeechSegment> = segments.filter {
        it.bookUrl == bookUrl && it.chapterIndex == chapterIndex
    }

    override suspend fun getChapterSummaries(
        bookUrl: String,
    ): List<ChapterSpeechSummary> = emptyList()

    override suspend fun setSegmentUserLocked(segmentId: String, locked: Boolean) {
        segments = segments.map {
            if (it.id == segmentId) it.copy(userLocked = locked) else it
        }
    }

    override suspend fun setSegmentAttribution(
        segmentId: String,
        roleType: SpeechRoleType,
        characterId: String?,
        characterName: String,
        userLocked: Boolean,
    ) {
        segments = segments.map {
            if (it.id == segmentId) {
                it.copy(
                    roleType = roleType,
                    characterId = characterId,
                    characterName = characterName,
                    userLocked = userLocked,
                )
            } else {
                it
            }
        }
    }

    override suspend fun deleteBook(bookUrl: String) {
        analysis = null
        segments = emptyList()
    }

    override suspend fun deleteChapter(bookUrl: String, chapterIndex: Int) {
        analysis = null
        segments = emptyList()
    }
}
