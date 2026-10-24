package io.legado.app.help.readaloud.script

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSseAudioStreamTest {

    private fun sseStream(
        body: String,
        config: TtsSseConfig = TtsSseConfig(),
    ): TtsSseAudioInputStream {
        val buffer = Buffer().writeUtf8(body)
        return TtsSseAudioInputStream(buffer, config)
    }

    private fun TtsSseAudioInputStream.readAll(): ByteArray {
        return use { stream ->
            val out = mutableListOf<Byte>()
            val buffer = ByteArray(512)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                out.addAll(buffer.take(count))
            }
            out.toByteArray()
        }
    }

    @Test
    fun `reads wav header for pcm stream`() {
        val stream = sseStream("", TtsSseConfig(pcm = TtsPcmConfig(sampleRate = 24000)))
        val header = ByteArray(44)
        var offset = 0
        while (offset < 44) {
            val count = stream.read(header, offset, 44 - offset)
            if (count < 0) break
            offset += count
        }
        assertEquals(44, offset)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(header, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        // PCM16 单声道 24kHz 的 byteRate = 24000 * 2
        val byteRate = (header[28].toInt() and 0xff) or
            ((header[29].toInt() and 0xff) shl 8) or
            ((header[30].toInt() and 0xff) shl 16) or
            ((header[31].toInt() and 0xff) shl 24)
        assertEquals(48000, byteRate)
    }

    @Test
    fun `aggregates data events and extracts base64 audio by rule`() {
        val body = """
            : heartbeat

            data: {"event":"start"}

            data: {"type":"3","buffer":"QUJDREVG"}

            data: [DONE]
        """.trimIndent() + "\n"
        val config = TtsSseConfig(
            textRules = listOf(
                TtsWebSocketTextRule(matchPath = "event", equalsValue = "start"),
                TtsWebSocketTextRule(
                    matchPath = "type",
                    equalsValue = "3",
                    audioPath = "buffer",
                    audioEncoding = "base64",
                ),
            )
        )
        val bytes = sseStream(body, config).readAll()
        // WAV 头（无 pcm 配置时为空）+ base64 解出的 "ABCDEF"
        assertEquals("ABCDEF", String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `error rule surfaces message`() {
        val body = "data: {\"error\":{\"message\":\"quota exceeded\"}}\n\n"
        val config = TtsSseConfig(
            textRules = listOf(
                TtsWebSocketTextRule(matchPath = "error.message", errorPath = "error.message"),
            )
        )
        val error = runCatching { sseStream(body, config).readAll() }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("quota exceeded"))
    }

    @Test
    fun `eof without finish marker ends stream by default`() {
        val body = "data: {\"audio\":\"WQ==\"}\n\n"
        val config = TtsSseConfig(
            textRules = listOf(
                TtsWebSocketTextRule(matchPath = "audio", audioPath = "audio"),
            )
        )
        val bytes = sseStream(body, config).readAll()
        assertEquals("Y", String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `custom data prefix and done marker`() {
        val body = """
            event: add
            data:{"audio":"WQ=="}
            data:[END]
        """.trimIndent() + "\n"
        val config = TtsSseConfig(
            doneData = "[END]",
            textRules = listOf(
                TtsWebSocketTextRule(matchPath = "audio", audioPath = "audio"),
            ),
        )
        val bytes = sseStream(body, config).readAll()
        assertEquals("Y", String(bytes, Charsets.UTF_8))
    }

    @Test
    fun `elementAtPath resolves nested arrays`() {
        val root = com.google.gson.JsonParser.parseString(
            """{"a":{"b":[{"c":"hit"}]}}""",
        )
        assertEquals("hit", root.elementAtPath("a.b[0].c")?.primitiveText())
        assertNull(root.elementAtPath("a.b[5].c")?.primitiveText())
        assertNull(root.elementAtPath("x.y")?.primitiveText())
    }
}
