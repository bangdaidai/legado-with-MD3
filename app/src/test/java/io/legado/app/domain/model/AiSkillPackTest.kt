package io.legado.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSkillPackTest {

    private fun fullPack() = AiSkillPack(
        name = "文言文直译",
        description = "测试包",
        items = listOf(
            AiSkillPackItem(
                type = AiSkillPackCodec.TYPE_TASK_PRESET,
                taskType = AiTaskType.TRANSLATE_CHAPTER,
                name = "文言文直译",
                promptTemplate = "将下面的文言文直接译为白话，保留语序。",
                params = AiGenerationParams(
                    temperature = 0.3f,
                    maxOutputTokens = 4096,
                    topP = 0.9f,
                    reasoningLevel = AiReasoningLevel.MEDIUM,
                    webSearch = false
                ),
                runtimeOptions = AiTaskRuntimeOptions(
                    targetLanguage = "zh",
                    maxInputChars = 10_000,
                    concurrentRequests = 2,
                    retryCount = 1
                ),
                modelRef = AiSkillModelRef(
                    providerName = "DeepSeek",
                    protocol = "openai_chat_completions",
                    modelName = "DeepSeek Chat",
                    modelId = "deepseek-chat"
                )
            ),
            AiSkillPackItem(
                type = AiSkillPackCodec.TYPE_PROMPT_PRESET,
                taskType = AiTaskType.REWRITE_TEXT,
                name = "去翻译腔",
                instruction = "把译文的翻译腔改写为自然的中文表达。"
            )
        )
    )

    @Test
    fun roundTripKeepsAllFields() {
        val json = AiSkillPackCodec.encode(fullPack())
        val decoded = AiSkillPackCodec.decode(json)
        assertTrue(decoded is AiSkillPackDecodeResult.Success)
        assertEquals(fullPack(), (decoded as AiSkillPackDecodeResult.Success).pack)
    }

    @Test
    fun decodeRejectsForeignKind() {
        val json = AiSkillPackCodec.encode(fullPack()).replace(
            "\"legado-ai-skill-pack\"",
            "\"something-else\""
        )
        assertTrue(AiSkillPackCodec.decode(json) is AiSkillPackDecodeResult.Failure)
    }

    @Test
    fun decodeRejectsGarbage() {
        assertTrue(AiSkillPackCodec.decode("not a json") is AiSkillPackDecodeResult.Failure)
        assertTrue(AiSkillPackCodec.decode("[1,2,3]") is AiSkillPackDecodeResult.Failure)
        assertTrue(AiSkillPackCodec.decode("") is AiSkillPackDecodeResult.Failure)
    }

    @Test
    fun decodeDropsUnknownTaskType() {
        val json = """
            {
              "kind": "${AiSkillPackCodec.KIND}",
              "schemaVersion": 1,
              "name": "t",
              "items": [
                {"type": "task_preset", "taskType": "not_a_task", "name": "x",
                 "promptTemplate": "p"},
                {"type": "task_preset", "taskType": "chat", "name": "ok",
                 "promptTemplate": "p"}
              ]
            }
        """.trimIndent()
        val decoded = AiSkillPackCodec.decode(json)
        assertTrue(decoded is AiSkillPackDecodeResult.Success)
        assertEquals(1, (decoded as AiSkillPackDecodeResult.Success).pack.items.size)
        assertEquals(AiTaskType.CHAT, decoded.pack.items.single().taskType)
    }

    @Test
    fun decodeDropsAllUnknownItemsAsFailure() {
        val json = """
            {
              "kind": "${AiSkillPackCodec.KIND}",
              "schemaVersion": 1,
              "name": "t",
              "items": [
                {"type": "task_preset", "taskType": "not_a_task", "name": "x",
                 "promptTemplate": "p"}
              ]
            }
        """.trimIndent()
        assertTrue(AiSkillPackCodec.decode(json) is AiSkillPackDecodeResult.Failure)
    }

    @Test
    fun decodeClampsParams() {
        val json = """
            {
              "kind": "${AiSkillPackCodec.KIND}",
              "schemaVersion": 1,
              "name": "t",
              "items": [
                {"type": "task_preset", "taskType": "translate_chapter", "name": "n",
                 "promptTemplate": "p",
                 "params": {"temperature": 5, "maxOutputTokens": 0, "topP": 3,
                            "reasoningLevel": "NOT_A_LEVEL", "webSearch": true},
                 "runtimeOptions": {"targetLanguage": "", "maxInputChars": 1,
                                    "concurrentRequests": 99, "retryCount": -1}}
              ]
            }
        """.trimIndent()
        val decoded = AiSkillPackCodec.decode(json)
        assertTrue(decoded is AiSkillPackDecodeResult.Success)
        val item = (decoded as AiSkillPackDecodeResult.Success).pack.items.single()
        val params = item.params!!
        assertEquals(2f, params.temperature)
        assertEquals(1, params.maxOutputTokens)
        assertEquals(1f, params.topP)
        assertEquals(AiReasoningLevel.AUTO, params.reasoningLevel)
        assertTrue(params.webSearch)
        val options = item.runtimeOptions!!
        assertEquals(AiTaskRuntimeOptions.DEFAULT_TARGET_LANGUAGE, options.targetLanguage)
        assertEquals(100, options.maxInputChars)
        assertEquals(16, options.concurrentRequests)
        assertEquals(0, options.retryCount)
    }

    @Test
    fun decodeRejectsOversizePrompt() {
        val oversized = "x".repeat(8_001)
        val json = """
            {
              "kind": "${AiSkillPackCodec.KIND}",
              "schemaVersion": 1,
              "name": "t",
              "items": [
                {"type": "task_preset", "taskType": "chat", "name": "n",
                 "promptTemplate": "$oversized"}
              ]
            }
        """.trimIndent()
        assertTrue(AiSkillPackCodec.decode(json) is AiSkillPackDecodeResult.Failure)
    }

    @Test
    fun parseParamsJsonToleratesGarbage() {
        assertNull(AiSkillPackCodec.parseParamsJson("not a json"))
        assertNull(AiSkillPackCodec.parseParamsJson(null))
        assertNull(AiSkillPackCodec.parseParamsJson(""))
        val parsed = AiSkillPackCodec.parseParamsJson("""{"temperature":0.5}""")
        assertEquals(0.5f, parsed?.temperature)
    }
}
