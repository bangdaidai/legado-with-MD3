package io.legado.app.domain.model.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechVoiceRouterTest {

    @Test
    fun `uses primary voice supported by backend`() {
        val primary = voice("http-primary", ReadAloudVoice.ENGINE_HTTP)
        val routed = SpeechVoiceRouter.route(
            cue = cue(primary),
            supportedEngineTypes = setOf(ReadAloudVoice.ENGINE_HTTP),
            defaultRoute = SpeechEngineRoute(ReadAloudVoice.ENGINE_HTTP, "default"),
        )

        assertEquals(primary, routed.effectiveVoice)
        assertEquals(SpeechVoiceStatus.Applied, routed.status)
    }

    @Test
    fun `skips incompatible and unavailable voices before fallback`() {
        val system = voice("system", ReadAloudVoice.ENGINE_SYSTEM)
        val unavailable = voice("unavailable", ReadAloudVoice.ENGINE_HTTP).copy(available = false)
        val fallback = voice("fallback", ReadAloudVoice.ENGINE_HTTP)
        val routed = SpeechVoiceRouter.route(
            cue = cue(system, listOf(unavailable, fallback)),
            supportedEngineTypes = setOf(ReadAloudVoice.ENGINE_HTTP),
            defaultRoute = SpeechEngineRoute(ReadAloudVoice.ENGINE_HTTP, "default"),
        )

        assertEquals(fallback, routed.effectiveVoice)
        assertEquals(SpeechVoiceStatus.FellBack, routed.status)
        assertTrue(routed.reason.isNotBlank())
    }

    @Test
    fun `creates runtime route when no configured voice is executable`() {
        val routed = SpeechVoiceRouter.route(
            cue = cue(null),
            supportedEngineTypes = setOf(ReadAloudVoice.ENGINE_HTTP),
            defaultRoute = SpeechEngineRoute(ReadAloudVoice.ENGINE_HTTP, "42"),
        )

        assertEquals(ReadAloudVoice.ENGINE_HTTP, routed.effectiveVoice?.engineType)
        assertEquals("42", routed.effectiveVoice?.engineId)
        // 没指定音色就用引擎默认声，属于预期结果，不该报成差异
        assertEquals(SpeechVoiceStatus.Applied, routed.status)
    }

    @Test
    fun `reports engine mismatch when planned voice belongs to another backend`() {
        val cloud = voice("cloud", ReadAloudVoice.ENGINE_CLOUD)
        val routed = SpeechVoiceRouter.route(
            cue = cue(cloud),
            supportedEngineTypes = setOf(ReadAloudVoice.ENGINE_SYSTEM),
            defaultRoute = SpeechEngineRoute(ReadAloudVoice.ENGINE_SYSTEM, "default"),
        )

        assertEquals(SpeechVoiceStatus.EngineMismatch, routed.status)
        assertTrue(routed.effectiveVoice?.speakerId.orEmpty().isEmpty())
        assertTrue(routed.reason.isNotBlank())
    }

    @Test
    fun `reports engine default when planned voice is excluded`() {
        val excluded = voice("excluded", ReadAloudVoice.ENGINE_HTTP).copy(excluded = true)
        val routed = SpeechVoiceRouter.route(
            cue = cue(excluded),
            supportedEngineTypes = setOf(ReadAloudVoice.ENGINE_HTTP),
            defaultRoute = SpeechEngineRoute(ReadAloudVoice.ENGINE_HTTP, "default"),
        )

        assertEquals(SpeechVoiceStatus.EngineDefault, routed.status)
        assertTrue(routed.reason.contains("已被排除"))
    }

    @Test
    fun `keeps cloud voice on mixed engine playback coordinator`() {
        val cloud = voice("cloud", ReadAloudVoice.ENGINE_CLOUD)
        val routed = SpeechVoiceRouter.route(
            cue = cue(cloud),
            supportedEngineTypes = setOf(
                ReadAloudVoice.ENGINE_HTTP,
                ReadAloudVoice.ENGINE_SYSTEM,
                ReadAloudVoice.ENGINE_CLOUD,
            ),
            defaultRoute = SpeechEngineRoute(ReadAloudVoice.ENGINE_SYSTEM, "default"),
        )

        assertEquals(ReadAloudVoice.ENGINE_CLOUD, routed.effectiveVoice?.engineType)
        assertEquals(cloud, routed.effectiveVoice)
        assertEquals(SpeechVoiceStatus.Applied, routed.status)
    }

    private fun voice(id: String, type: String) = ReadAloudVoice(
        id = id,
        engineType = type,
        engineId = id,
        speakerId = "",
        displayName = id,
    )

    private fun cue(
        voice: ReadAloudVoice?,
        fallbacks: List<ReadAloudVoice> = emptyList(),
    ) = ReadAloudPlaybackCue(
        text = "text",
        chapterStart = 0,
        chapterEnd = 4,
        paragraphIndex = 0,
        voice = voice,
        fallbackVoices = fallbacks,
        roleType = SpeechRoleType.Narrator,
        characterId = null,
    )
}