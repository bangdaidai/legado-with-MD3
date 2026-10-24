package io.legado.app.help.readaloud.script

import io.legado.app.data.entities.HttpTTS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsScriptRequestTest {

    private val source = HttpTTS(
        id = 1L,
        name = "脚本引擎",
        contentType = "audio/x-wav",
        script = "function synthesize() {}",
    )

    @Test
    fun `plain url result becomes audio request`() {
        val request = TtsScriptEngineClient.parseSynthesisRequest(
            "https://tts.example.com/audio.mp3", source,
        )
        assertEquals("https://tts.example.com/audio.mp3", request.url)
        assertEquals("audio", request.responseType)
        assertEquals("audio/x-wav", request.audioContentType)
        assertTrue(!request.isJsonResponse)
        assertEquals("https://tts.example.com/audio.mp3", request.toAnalyzeUrlRule())
    }

    @Test
    fun `object result is parsed with aliases and defaults`() {
        val request = TtsScriptEngineClient.parseSynthesisRequest(
            """
            {
                "url": "https://api.example.com/tts",
                "method": "POST",
                "headers": {"api-key": "k1"},
                "body": "{\"text\":\"hi\"}",
                "requestContentType": "application/json",
                "audio_type": "audio/mpeg",
                "audioExtract": "choices[0].message.audio.data",
                "timeout": 999,
                "retry": 9
            }
            """.trimIndent(),
            source,
        )
        assertEquals("https://api.example.com/tts", request.url)
        assertEquals("POST", request.method)
        assertEquals(mapOf("api-key" to "k1"), request.headers)
        assertEquals("""{"text":"hi"}""", request.body)
        assertEquals("audio/mpeg", request.audioContentType)
        assertEquals("json", request.responseType)
        assertTrue(request.isJsonResponse)
        assertEquals("choices[0].message.audio.data", request.audioExtract)
        // timeout 钳制到 300s，retry 钳制到 3
        assertEquals(300 * 1000L, request.timeoutMillis)
        assertEquals(3, request.retry.coerceIn(0, 3))
    }

    @Test
    fun `stream object infers sse transport`() {
        val request = TtsScriptEngineClient.parseSynthesisRequest(
            """
            {
                "url": "https://api.example.com/chat",
                "method": "POST",
                "body": "{}",
                "stream": {
                    "dataPrefix": "data:",
                    "pcm": {"sampleRate": 24000}
                }
            }
            """.trimIndent(),
            source,
        )
        assertTrue(request.isSse)
        val sse = request.sseConfig!!
        assertEquals(24000, sse.pcm!!.sampleRate)
        assertEquals("[DONE]", sse.doneData)
    }

    @Test
    fun `missing url throws`() {
        val error = runCatching {
            TtsScriptEngineClient.parseSynthesisRequest("""{"method": "POST"}""", source)
        }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("url"))
    }

    @Test
    fun `toAnalyzeUrlRule embeds method headers and body`() {
        val request = TtsScriptEngineClient.TtsScriptRequest(
            url = "https://api.example.com/tts",
            method = "POST",
            headers = mapOf("Authorization" to "Bearer t"),
            body = "hello",
            requestContentType = "application/ssml+xml",
        )
        val rule = request.toAnalyzeUrlRule()
        assertTrue(rule.startsWith("https://api.example.com/tts,"))
        assertTrue(rule.contains("\"method\":\"POST\""))
        assertTrue(rule.contains("Authorization"))
        assertTrue(rule.contains("Content-Type"))
        assertTrue(rule.contains("\"body\":\"hello\""))
    }

    @Test
    fun `extractAudioValue explicit path and auto probe`() {
        val body = """{"data":{"audio":"QUJD","msg":"ok"}}"""
        assertEquals("QUJD", TtsScriptEngineClient.extractAudioValue(body, "data.audio"))
        assertEquals("QUJD", TtsScriptEngineClient.extractAudioValue(body, "$.data.audio"))
        // 数组下标路径
        val arrayBody = """{"items":[{"audio":"QUJD"}]}"""
        assertEquals("QUJD", TtsScriptEngineClient.extractAudioValue(arrayBody, "items[0].audio"))
        // 无路径时自动探测常见键
        assertEquals("QUJD", TtsScriptEngineClient.extractAudioValue("""{"audio":"QUJD"}""", null))
        assertNull(TtsScriptEngineClient.extractAudioValue("""{"other":1}""", "nope.path"))
    }
}
