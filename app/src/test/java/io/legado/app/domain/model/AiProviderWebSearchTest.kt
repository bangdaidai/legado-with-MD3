package io.legado.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这份映射决定了 Tavily 兜底会不会和供应商自带联网重复检索，判错就是双份费用。
 */
class AiProviderWebSearchTest {

    @Test
    fun anthropicAndResponsesProtocolsAlwaysHaveNativeSearch() {
        assertEquals(
            AiNativeWebSearchSupport.ANTHROPIC_WEB_SEARCH,
            provider(protocol = AiProtocol.ANTHROPIC_MESSAGES).nativeWebSearchSupport()
        )
        assertEquals(
            AiNativeWebSearchSupport.OPENAI_RESPONSES_WEB_SEARCH,
            provider(protocol = AiProtocol.OPENAI_RESPONSES).nativeWebSearchSupport()
        )
    }

    @Test
    fun qwenIsRecognizedByIdNameOrBaseUrl() {
        assertEquals(
            AiNativeWebSearchSupport.QWEN_ENABLE_SEARCH,
            provider(id = "qwen").nativeWebSearchSupport()
        )
        assertEquals(
            AiNativeWebSearchSupport.QWEN_ENABLE_SEARCH,
            provider(name = "阿里百炼 Bailian").nativeWebSearchSupport()
        )
        assertEquals(
            AiNativeWebSearchSupport.QWEN_ENABLE_SEARCH,
            provider(baseUrl = "https://DashScope.aliyuncs.com/compatible-mode/v1")
                .nativeWebSearchSupport()
        )
    }

    @Test
    fun zhipuIsRecognizedByBigmodelEndpoint() {
        assertEquals(
            AiNativeWebSearchSupport.ZHIPU_WEB_SEARCH_TOOL,
            provider(baseUrl = "https://open.bigmodel.cn/api/paas/v4").nativeWebSearchSupport()
        )
    }

    @Test
    fun plainChatCompletionsProvidersHaveNoNativeSearch() {
        val deepSeek = provider(id = "deepseek", name = "DeepSeek", baseUrl = "https://api.deepseek.com")
        assertEquals(AiNativeWebSearchSupport.NONE, deepSeek.nativeWebSearchSupport())
        assertFalse(deepSeek.nativeWebSearchSupport().isSupported)
    }

    @Test
    fun unknownProtocolsFallBackToNoSupport() {
        assertEquals(
            AiNativeWebSearchSupport.NONE,
            provider(protocol = AiProtocol.GOOGLE_TRANSLATE, id = "qwen").nativeWebSearchSupport()
        )
    }

    @Test
    fun supportedFlagMatchesTheEnumValue() {
        assertTrue(AiNativeWebSearchSupport.QWEN_ENABLE_SEARCH.isSupported)
        assertFalse(AiNativeWebSearchSupport.NONE.isSupported)
    }

    private fun provider(
        id: String = "custom",
        name: String = "Custom",
        protocol: String = AiProtocol.OPENAI_CHAT_COMPLETIONS,
        baseUrl: String = "https://example.com/v1",
    ) = AiProviderConfig(
        id = id,
        name = name,
        protocol = protocol,
        baseUrl = baseUrl,
        apiKey = "sk-test"
    )
}
