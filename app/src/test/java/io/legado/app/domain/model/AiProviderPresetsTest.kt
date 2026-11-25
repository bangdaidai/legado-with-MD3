package io.legado.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderPresetsTest {

    @Test
    fun addedProvidersUseExpectedNamesAndEndpoints() {
        val expected = mapOf(
            "siliconflow" to Triple(
                "硅基流动",
                "https://api.siliconflow.cn/v1",
                "https://api.siliconflow.cn/v1/models"
            ),
            "openrouter" to Triple(
                "OpenRouter",
                "https://openrouter.ai/api/v1",
                "https://openrouter.ai/api/v1/models"
            ),
            "sensenova" to Triple(
                "商汤",
                "https://token.sensenova.cn/v1",
                "https://token.sensenova.cn/v1/models"
            ),
            "aliyun_bailian" to Triple(
                "阿里云百炼",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "https://dashscope.aliyuncs.com/compatible-mode/v1/models"
            ),
            "volcengine" to Triple(
                "火山引擎",
                "https://ark.cn-beijing.volces.com/api/v3",
                "https://ark.cn-beijing.volces.com/api/v3/models"
            ),
            "moonshot" to Triple(
                "月之暗面",
                "https://api.moonshot.cn/v1",
                "https://api.moonshot.cn/v1/models"
            ),
            "zhipu" to Triple(
                "智谱",
                "https://open.bigmodel.cn/api/paas/v4",
                "https://open.bigmodel.cn/api/paas/v4/models"
            )
        )

        expected.forEach { (id, values) ->
            val preset = AiProviderPresets.items.single { it.id == id }
            assertEquals(AiProtocol.OPENAI_CHAT_COMPLETIONS, preset.protocol)
            assertEquals(values.first, preset.name)
            assertEquals(values.second, preset.baseUrl)
            assertEquals(values.third, preset.modelsUrl)
        }
    }

    @Test
    fun onlySenseNovaHasAnAddedDefaultModel() {
        assertEquals(
            "sensenova-6.7-flash-lite",
            AiProviderPresets.items.single { it.id == "sensenova" }.modelId
        )
        listOf("siliconflow", "openrouter", "aliyun_bailian", "volcengine", "moonshot", "zhipu")
            .forEach { id ->
                val preset = AiProviderPresets.items.single { it.id == id }
                assertTrue(preset.modelId.isBlank())
                assertTrue(preset.modelName.isBlank())
            }
    }

    @Test
    fun presetIdsAreUnique() {
        val ids = AiProviderPresets.items.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }
}
