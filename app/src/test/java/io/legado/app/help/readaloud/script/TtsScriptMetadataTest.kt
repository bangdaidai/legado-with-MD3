package io.legado.app.help.readaloud.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsScriptMetadataTest {

    @Test
    fun `parses script header metadata`() {
        val script = """
            // @name Xiaomi MiMo TTS
            // @uuid mimo_v25_tts
            // @version 1.0.2
            // @audioType audio/x-wav
            // @sampleText 前不见古人
            // @capabilities style_tags,emotion, synthesis_speed
            // @enabled false
            // @cookieJar true
            // @maxConcurrency 2
            function synthesize() {}
        """.trimIndent()
        val meta = TtsScriptMetadata.parse(script)
        assertEquals("Xiaomi MiMo TTS", meta.name)
        assertEquals("mimo_v25_tts", meta.uuid)
        assertEquals("1.0.2", meta.version)
        assertEquals("audio/x-wav", meta.audioType)
        assertEquals("前不见古人", meta.sampleText)
        assertEquals(listOf("style_tags", "emotion", "synthesis_speed"), meta.capabilities)
        assertFalse(meta.enabled)
        assertTrue(meta.cookieJar)
        assertEquals(2, meta.maxConcurrency)
    }

    @Test
    fun `blank script yields empty metadata`() {
        val meta = TtsScriptMetadata.parse(null)
        assertTrue(meta.capabilities.isEmpty())
        assertTrue(meta.enabled)
        assertFalse(meta.cookieJar)
        assertEquals(0, meta.maxConcurrency)
    }

    @Test
    fun `non meta comment lines are ignored`() {
        val script = """
            // 普通注释
            // @name Demo
            var config = {};
        """.trimIndent()
        assertEquals("Demo", TtsScriptMetadata.parse(script).name)
    }
}
