package io.legado.app.data.repository.ai

import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiGenerationParams
import io.legado.app.domain.model.AiHttpException
import io.legado.app.domain.model.AiModelConfig
import io.legado.app.domain.model.AiProtocol
import io.legado.app.domain.model.AiProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 盯 [AiOutputCeilings] 的读数口径。这些规则全靠供应商的错误原文子串，
 * 没有真机可复现，只能把各家说过的话钉成用例：
 * 智谱 `[1,16384]`、OpenAI 系 "at most N"、以及只说非法不给数字的兜底对半砍。
 */
class AiOutputCeilingsTest {

    @Test
    fun zhipuBracketRangeIsReadAsUpperBound() {
        val rejection = AiHttpException(
            400,
            "Bad Request",
            """{"error":{"code":"1210","message":"max_tokens参数非法：限制数值范围[1,16384]"}}"""
        )

        assertEquals(
            16_384,
            AiOutputCeilings.lowerAfterRejection(22_000, AiOutputCeilings.rejectionText(rejection))
        )
    }

    @Test
    fun tooLargeNumberIsNotMistakenForTheUpperBound() {
        // 一条消息里前半是"你请求了多少"，后半才是那条线：读成 22000 就永远降不动档
        val rejection = AiHttpException(
            400,
            null,
            "max_tokens is too large: 22000. This model supports at most 16384 completion tokens"
        )

        assertEquals(
            16_384,
            AiOutputCeilings.lowerAfterRejection(22_000, AiOutputCeilings.rejectionText(rejection))
        )
    }

    @Test
    fun rejectsWithoutANumberHalveTheBudget() {
        val rejection = AiHttpException(400, null, "invalid parameter: max_tokens")

        assertEquals(
            11_000,
            AiOutputCeilings.lowerAfterRejection(22_000, AiOutputCeilings.rejectionText(rejection))
        )
    }

    @Test
    fun aLimitWrittenWithoutAnyKeywordIsStillReadFromTheBareNumber() {
        // "limit is 8192 tokens" 没进关键词表，兜底得能从裸数字里把上限读出来
        val rejection = AiHttpException(400, null, "max_tokens exceeds limit is 8192 tokens")

        assertEquals(
            8_192,
            AiOutputCeilings.lowerAfterRejection(22_000, AiOutputCeilings.rejectionText(rejection))
        )
    }

    @Test
    fun doesNotRaiseTheBudgetWhenTheStatedBoundIsLargerThanOurs() {
        // 撞的不是预算（比如思考预算超限），降档重发只会白白发一次请求
        val rejection = AiHttpException(
            400,
            null,
            """{"error":{"message":"max_tokens参数非法：限制数值范围[1,16384]"}}"""
        )

        assertNull(AiOutputCeilings.lowerAfterRejection(8_000, AiOutputCeilings.rejectionText(rejection)))
    }

    @Test
    fun stopsLoweringOnceTheBudgetIsUseless() {
        val rejection = AiHttpException(400, null, "max_tokens exceeds the model limit")

        assertNull(AiOutputCeilings.lowerAfterRejection(1_024, AiOutputCeilings.rejectionText(rejection)))
    }

    @Test
    fun rateLimitIsNotABudgetRejection() {
        val throttled = AiHttpException(429, null, "max_tokens mentioned but this is throttling")
        val overBudget = AiHttpException(400, null, "max_tokens 非法")
        val wrongKey = AiHttpException(401, null, "invalid api key")

        assertFalse(AiOutputCeilings.isMaxTokensRejection(throttled))
        assertTrue(AiOutputCeilings.isMaxTokensRejection(overBudget))
        assertFalse(AiOutputCeilings.isMaxTokensRejection(wrongKey))
    }

    @Test
    fun declaredLimitBeatsTheLearnedOne() {
        val request = request(modelKey = "model-declared", declaredLimit = 4_096, budget = 22_000)
        val key = AiOutputCeilings.keyFor(request.model)
        AiOutputCeilings.remember(key, 16_384)

        assertEquals(4_096, AiOutputCeilings.ceilingOf(key, request.model.maxOutputTokens))
        assertEquals(4_096, AiOutputCeilings.clampTo(request, 4_096).params.maxOutputTokens)
    }

    @Test
    fun learnedLimitAppliesWhenTheModelSaysNothing() {
        val request = request(modelKey = "model-learned", declaredLimit = 0, budget = 22_000)
        val key = AiOutputCeilings.keyFor(request.model)
        AiOutputCeilings.remember(key, 16_384)

        assertEquals(16_384, AiOutputCeilings.ceilingOf(key, request.model.maxOutputTokens))
    }

    @Test
    fun learnedLimitNeverGrowsBack() {
        val key = "model-shrink"
        AiOutputCeilings.remember(key, 16_384)
        AiOutputCeilings.remember(key, 8_192)
        AiOutputCeilings.remember(key, 32_000)

        assertEquals(8_192, AiOutputCeilings.ceilingOf(key, declaredLimit = 0))
    }

    @Test
    fun budgetAlreadyInsideTheCeilingIsLeftAlone() {
        val request = request(modelKey = "model-clamp", declaredLimit = 0, budget = 8_000)

        assertSame(request, AiOutputCeilings.clampTo(request, 16_384))
    }

    private fun request(modelKey: String, declaredLimit: Int, budget: Int) = AiGenerateRequest(
        model = AiModelConfig(
            id = modelKey,
            provider = AiProviderConfig(
                id = "zhipu",
                name = "Zhipu AI",
                protocol = AiProtocol.OPENAI_CHAT_COMPLETIONS,
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                apiKey = "test"
            ),
            displayName = "GLM-4-Flash",
            modelId = "glm-4-flash",
            maxOutputTokens = declaredLimit
        ),
        messages = emptyList(),
        params = AiGenerationParams(maxOutputTokens = budget)
    )
}
