package io.legado.app.domain.usecase

import io.legado.app.data.entities.AiModelProfile
import io.legado.app.data.entities.AiProviderProfile
import io.legado.app.data.entities.AiTaskPreset
import io.legado.app.data.entities.BookCharacterEvent
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.data.entities.BookCharacterRelation
import io.legado.app.data.entities.BookKnowledgeEntry
import io.legado.app.data.entities.BookOutlineNode
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.gateway.AiStreamEvent
import io.legado.app.domain.gateway.AiTextGateway
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.AiAvailableModel
import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiGenerateResponse
import io.legado.app.domain.model.AiGenerationParams
import io.legado.app.domain.model.AiModelConfig
import io.legado.app.domain.model.AiModelDraft
import io.legado.app.domain.model.AiProfileDraft
import io.legado.app.domain.model.AiProtocol
import io.legado.app.domain.model.AiProviderConfig
import io.legado.app.domain.model.AiProviderDraft
import io.legado.app.domain.model.AiTaskPresetConfig
import io.legado.app.domain.model.AiTaskType
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysis
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysisResult
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.ChapterSpeechSummary
import io.legado.app.domain.model.readaloud.SpeechAnalysisMode
import io.legado.app.domain.model.readaloud.SpeechAnalysisStatus
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 说话人分析的本书级冷却边界。
 *
 * 盯的是那个让「一直准备中」没完没了的坑：仓库层 `withTimeout` 造的
 * [TimeoutCancellationException] 也是 [CancellationException] 的子类，一旦和「用户翻页取消」
 * 混成一类，超时就会永远进不了冷却，于是每次起播、每一章预合成都重撞一次几十秒的超时。
 */
class RefineSpeechWithAiUseCaseTest {

    @Test
    fun `a timed out analysis cools the whole book down`() = runBlocking {
        val speech = MemoryChapterSpeechGateway()
        val ai = FailingAiTextGateway(captureTimeout())
        val useCase = refineUseCase(ai, speech)
        val analysis = ruleAnalysis()

        val error = runCatching {
            useCase(analysis, emptyList(), SpeechAnalysisMode.RuleWithAi, now = 1_000)
        }.exceptionOrNull()

        assertTrue(
            "超时要把 TimeoutCancellationException 原样抛给上层，才能弹回落提示",
            error is TimeoutCancellationException,
        )
        assertEquals(1, ai.calls)
        // 超时是这次分析真正的原因，分镜页得能看到，不能停在规则那档
        assertEquals(SpeechAnalysisStatus.Failed, speech.analysis?.status)
        assertTrue(speech.analysis?.error?.isNotBlank() == true)

        // 冷却窗口内的第二次调用（下一章、预合成、分镜页）不再重打 AI，直接拿规则结果
        val second = useCase(analysis, emptyList(), SpeechAnalysisMode.RuleWithAi, now = 2_000)

        assertEquals("冷却期内不该再发 AI 请求", 1, ai.calls)
        assertSame(analysis, second)
    }

    @Test
    fun `a user cancellation keeps the book out of cooldown`() = runBlocking {
        val speech = MemoryChapterSpeechGateway()
        val ai = FailingAiTextGateway(CancellationException("Job was cancelled"))
        val useCase = refineUseCase(ai, speech)
        val analysis = ruleAnalysis()

        runCatching {
            useCase(analysis, emptyList(), SpeechAnalysisMode.RuleWithAi, now = 1_000)
        }

        assertEquals(1, ai.calls)
        assertNull("翻页取消不该把分析标成失败", speech.analysis)

        val retry = runCatching {
            useCase(analysis, emptyList(), SpeechAnalysisMode.RuleWithAi, now = 2_000)
        }.exceptionOrNull()

        assertTrue("取消不是失败，下一次仍然要试 AI", retry is CancellationException)
        assertEquals(2, ai.calls)
    }

    @Test
    fun `the book leaves cooldown once the window passes`() = runBlocking {
        val ai = FailingAiTextGateway(captureTimeout())
        val useCase = refineUseCase(ai, MemoryChapterSpeechGateway())
        val analysis = ruleAnalysis()

        runCatching {
            useCase(analysis, emptyList(), SpeechAnalysisMode.RuleWithAi, now = 0)
        }
        runCatching {
            useCase(
                analysis,
                emptyList(),
                SpeechAnalysisMode.RuleWithAi,
                // 与 AI_FAILURE_COOLDOWN_MS 对齐：刚过窗口就该再给 AI 一次机会
                now = AI_FAILURE_WINDOW_MS + 1,
            )
        }

        assertEquals(2, ai.calls)
    }

    private companion object {
        /** RefineSpeechWithAiUseCase 里 AI_FAILURE_COOLDOWN_MS 的当前取值。 */
        const val AI_FAILURE_WINDOW_MS = 10 * 60 * 1000L
    }

    private fun refineUseCase(
        ai: AiTextGateway,
        speech: ChapterSpeechGateway,
    ) = RefineSpeechWithAiUseCase(
        aiProfileGateway = FakeAiProfileGateway(preset),
        aiTextGateway = ai,
        bookKnowledgeGateway = FakeBookKnowledgeGateway(),
        chapterSpeechGateway = speech,
    )

    /** 一句「旁白 + 对白」：对白没有角色归属，所以必然成为 AI 复核的候选段。 */
    private fun ruleAnalysis() = ChapterSpeechAnalysisResult(
        analysis = ChapterSpeechAnalysis(
            id = "analysis-1",
            bookUrl = BOOK_URL,
            chapterIndex = 3,
            contentHash = "hash",
            resolverVersion = "rule-v1",
            characterRevision = "",
            status = SpeechAnalysisStatus.Partial,
            createdAt = 100,
            updatedAt = 100,
        ),
        segments = listOf(
            ChapterSpeechSegment(
                id = "segment-1",
                analysisId = "analysis-1",
                bookUrl = BOOK_URL,
                chapterIndex = 3,
                paragraphIndex = 0,
                start = 2,
                end = 6,
                chapterPosition = 2,
                text = "“你好”",
                roleType = SpeechRoleType.Character,
                confidence = 0.4f,
                source = SpeechResolutionSource.Rule,
                createdAt = 100,
                updatedAt = 100,
            ),
        ),
        fromCache = false,
    )

    private val preset = AiTaskPresetConfig(
        id = "preset-speech",
        taskType = AiTaskType.ANALYZE_SPEECH,
        name = "Speech",
        model = AiModelConfig(
            id = "model-1",
            provider = AiProviderConfig(
                id = "zhipu",
                name = "Zhipu AI",
                protocol = AiProtocol.OPENAI_CHAT_COMPLETIONS,
                baseUrl = "https://example.test",
                apiKey = "key",
            ),
            displayName = "GLM-4-Flash",
            modelId = "glm-4-flash",
        ),
        promptTemplate = "prompt",
        params = AiGenerationParams(),
    )
}

private const val BOOK_URL = "legado-book"

/** 真造一个超时出来：[TimeoutCancellationException] 的构造器不对外暴露。 */
private suspend fun captureTimeout(): TimeoutCancellationException = try {
    withTimeout(1) { delay(10_000) }
    error("withTimeout 没有按预期触发")
} catch (e: TimeoutCancellationException) {
    e
}

private class FailingAiTextGateway(
    private val failure: Throwable,
) : AiTextGateway {
    var calls = 0
        private set

    override suspend fun generate(request: AiGenerateRequest): Result<AiGenerateResponse> {
        calls++
        // 与仓库层一致：超时不是抛出而是塞进 Result，由 use case 的 getOrThrow 还原
        return Result.failure(failure)
    }

    override fun generateStream(request: AiGenerateRequest): Flow<AiStreamEvent> = emptyFlow()

    override suspend fun fetchModels(provider: AiProviderConfig) =
        Result.success(emptyList<AiAvailableModel>())
}

private class FakeBookKnowledgeGateway : BookKnowledgeGateway {
    override suspend fun getBookName(bookUrl: String): String? = null
    override suspend fun searchCharacterProfiles(
        bookUrl: String,
        query: String,
        limit: Int,
    ): List<BookCharacterProfile> = emptyList()

    override suspend fun getCharacterProfiles(
        bookUrl: String,
        limit: Int,
        includeDrafts: Boolean,
    ): List<BookCharacterProfile> = emptyList()

    override suspend fun getCharacterProfile(
        bookUrl: String,
        idOrName: String,
    ): BookCharacterProfile? = null

    override suspend fun getCharacterEvents(
        bookUrl: String,
        characterId: String?,
        maxChapterIndex: Int?,
        limit: Int,
    ): List<BookCharacterEvent> = emptyList()

    override suspend fun getCharacterRelations(
        bookUrl: String,
        characterId: String,
        limit: Int,
    ): List<BookCharacterRelation> = emptyList()

    override suspend fun getBookCharacterRelations(
        bookUrl: String,
        limit: Int,
    ): List<BookCharacterRelation> = emptyList()

    override suspend fun searchKnowledgeEntries(
        bookUrl: String,
        query: String,
        type: String?,
        chapterIndex: Int?,
        limit: Int,
    ): List<BookKnowledgeEntry> = emptyList()

    override suspend fun getOutlineNodes(
        bookUrl: String,
        chapterIndex: Int?,
        nodeType: String?,
        limit: Int,
    ): List<BookOutlineNode> = emptyList()

    override suspend fun upsertCharacterProfile(profile: BookCharacterProfile) = Unit
    override suspend fun upsertCharacterEvent(event: BookCharacterEvent) = Unit
    override suspend fun upsertCharacterRelation(relation: BookCharacterRelation) = Unit
    override suspend fun upsertKnowledgeEntry(entry: BookKnowledgeEntry) = Unit
    override suspend fun upsertOutlineNode(node: BookOutlineNode) = Unit

    override suspend fun deleteCharacterProfile(
        bookUrl: String,
        characterId: String,
        deleteRelations: Boolean,
        deleteEvents: Boolean,
    ) = Unit

    override suspend fun deleteCharacterRelation(relationId: String) = Unit
    override suspend fun deleteKnowledgeEntry(entryId: String) = Unit
    override suspend fun migrateToNewBookUrl(oldBookUrl: String, newBookUrl: String) = Unit
}

private class FakeAiProfileGateway(
    private val preset: AiTaskPresetConfig,
) : AiProfileGateway {
    override fun observeProviders(): Flow<List<AiProviderProfile>> = emptyFlow()
    override fun observeModels(): Flow<List<AiModelProfile>> = emptyFlow()
    override fun observePresets(): Flow<List<AiTaskPreset>> = emptyFlow()
    override fun defaultPrompt(taskType: String): String = ""
    override suspend fun getProvider(id: String): AiProviderProfile? = null
    override suspend fun getModel(id: String): AiModelProfile? = null
    override suspend fun getTaskPreset(taskType: String) = preset
    override suspend fun getProviderApiKey(providerId: String) = ""
    override suspend fun saveProvider(draft: AiProviderDraft): AiProviderProfile = error("unused")
    override suspend fun saveModel(draft: AiModelDraft): AiModelProfile = error("unused")
    override suspend fun importProviderModels(
        providerId: String,
        models: List<AiAvailableModel>,
    ): List<AiModelProfile> = error("unused")

    override suspend fun setDefaultModel(modelProfileId: String): AiTaskPresetConfig =
        error("unused")

    override suspend fun setTaskPresetModel(
        taskType: String,
        modelProfileId: String,
    ): AiTaskPresetConfig = error("unused")

    override suspend fun saveDefaultChatProfile(draft: AiProfileDraft): AiTaskPresetConfig =
        error("unused")

    override suspend fun saveTaskPreset(
        taskType: String,
        promptTemplate: String,
        temperature: Float,
        maxOutputTokens: Int,
    ): AiTaskPresetConfig = error("unused")

    override suspend fun deleteProvider(providerId: String) = Unit
    override suspend fun deleteModel(modelId: String) = Unit
}

private class MemoryChapterSpeechGateway : ChapterSpeechGateway {
    var analysis: ChapterSpeechAnalysis? = null
        private set

    override suspend fun getAnalysis(
        bookUrl: String,
        chapterIndex: Int,
        contentHash: String,
        resolverVersion: String,
    ): ChapterSpeechAnalysis? = analysis

    override suspend fun upsertAnalysis(analysis: ChapterSpeechAnalysis) {
        this.analysis = analysis
    }

    override suspend fun saveAnalysis(
        analysis: ChapterSpeechAnalysis,
        segments: List<ChapterSpeechSegment>,
    ) {
        this.analysis = analysis
    }

    override suspend fun getSegments(analysisId: String): List<ChapterSpeechSegment> = emptyList()
    override suspend fun getChapterSegments(
        bookUrl: String,
        chapterIndex: Int,
    ): List<ChapterSpeechSegment> = emptyList()

    override suspend fun getChapterSummaries(bookUrl: String): List<ChapterSpeechSummary> =
        emptyList()

    override suspend fun replaceSegments(
        analysisId: String,
        segments: List<ChapterSpeechSegment>,
    ) = Unit

    override suspend fun deleteChapter(bookUrl: String, chapterIndex: Int) {
        analysis = null
    }

    override suspend fun deleteBook(bookUrl: String) {
        analysis = null
    }
}
