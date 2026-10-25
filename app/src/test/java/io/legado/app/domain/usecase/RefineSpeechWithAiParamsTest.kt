package io.legado.app.domain.usecase

import io.legado.app.domain.model.AiGenerationParams
import io.legado.app.domain.model.AiModelConfig
import io.legado.app.domain.model.AiProtocol
import io.legado.app.domain.model.AiProviderConfig
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.AiTaskPresetConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class RefineSpeechWithAiParamsTest {

    @Test
    fun `off disables thinking even when the model defaults to medium`() {
        val params = speechAnalysisParams(preset(AiReasoningLevel.MEDIUM), AiReasoningLevel.OFF)

        assertEquals(AiReasoningLevel.OFF, params.reasoningLevel)
        assertEquals(0f, params.temperature ?: -1f)
    }

    @Test
    fun `auto keeps the preset and model level`() {
        val params = speechAnalysisParams(preset(AiReasoningLevel.MEDIUM), AiReasoningLevel.AUTO)

        assertEquals(AiReasoningLevel.MEDIUM, params.reasoningLevel)
    }

    @Test
    fun `an explicit level overrides the model default`() {
        val params = speechAnalysisParams(preset(AiReasoningLevel.MEDIUM), AiReasoningLevel.HIGH)

        assertEquals(AiReasoningLevel.HIGH, params.reasoningLevel)
    }

    @Test
    fun `long chapters do not outbid the limit the model declares`() {
        // 第 14 章那条 400：智谱 GLM-4-Flash 只允许 16384，预算按分段数放大到 2.7 万直接整章失败
        val params = speechAnalysisParams(
            preset(AiReasoningLevel.OFF, maxOutputTokens = 16_384),
            AiReasoningLevel.OFF,
            decisionCount = 100,
        )

        assertEquals(16_384, params.maxOutputTokens)
    }

    @Test
    fun `an undeclared model still gets a provider-safe ceiling`() {
        val params = speechAnalysisParams(
            preset(AiReasoningLevel.OFF),
            AiReasoningLevel.OFF,
            decisionCount = 500,
        )

        assertEquals(16_000, params.maxOutputTokens)
    }

    @Test
    fun `a small model ceiling beats the fixed floor`() {
        // 4096 上限的模型：宁可在这里就压到 4096，也不要带着 8000 出门被拒
        val params = speechAnalysisParams(
            preset(AiReasoningLevel.OFF, maxOutputTokens = 4_096),
            AiReasoningLevel.OFF,
        )

        assertEquals(4_096, params.maxOutputTokens)
    }

    @Test
    fun `short chapters keep the thinking-sized floor`() {
        val params = speechAnalysisParams(preset(AiReasoningLevel.OFF), AiReasoningLevel.OFF)

        assertEquals(8_000, params.maxOutputTokens)
    }

    private fun preset(
        reasoningLevel: AiReasoningLevel,
        maxOutputTokens: Int = 0,
    ) = AiTaskPresetConfig(
        id = "preset-chat",
        taskType = "chat",
        name = "Default Chat",
        model = AiModelConfig(
            id = "model-1",
            provider = AiProviderConfig(
                id = "zhipu",
                name = "Zhipu AI",
                protocol = AiProtocol.OPENAI_CHAT_COMPLETIONS,
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                apiKey = "test"
            ),
            displayName = "GLM-4.7-Flash",
            modelId = "glm-4.7-flash",
            maxOutputTokens = maxOutputTokens,
            defaultParams = AiGenerationParams(reasoningLevel = reasoningLevel)
        ),
        promptTemplate = "prompt",
        params = AiGenerationParams(
            temperature = 0.7f,
            reasoningLevel = reasoningLevel
        )
    )
}
