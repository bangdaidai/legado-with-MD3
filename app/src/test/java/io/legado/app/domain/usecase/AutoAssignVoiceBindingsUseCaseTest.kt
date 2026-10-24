package io.legado.app.domain.usecase

import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.BookVoiceBinding
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动分配音色的结构化年龄段匹配分。
 *
 * 音色的年龄段从声音池派生（音色本身没有年龄字段），角色的来自档案 voiceAgeBand。
 * 匹配语义：精确 100 / 相邻 50 / 任一 unknown 0（退化到关键词） / 不匹配 -10。
 */
class AutoAssignVoiceBindingsUseCaseTest {

    private val useCase = AutoAssignVoiceBindingsUseCase(voiceGateway = FakeReadAloudVoiceGateway())

    @Test
    fun `exact age band match scores highest`() {
        assertEquals(
            100,
            useCase.ageBandMatchScore(
                BookCharacterProfile.VOICE_AGE_YOUNG_ADULT,
                BookCharacterProfile.VOICE_AGE_YOUNG_ADULT,
            ),
        )
    }

    @Test
    fun `adjacent age band scores partial`() {
        assertEquals(
            50,
            useCase.ageBandMatchScore(
                BookCharacterProfile.VOICE_AGE_ADULT,
                BookCharacterProfile.VOICE_AGE_YOUNG_ADULT,
            ),
        )
        assertEquals(
            50,
            useCase.ageBandMatchScore(
                BookCharacterProfile.VOICE_AGE_CHILD,
                BookCharacterProfile.VOICE_AGE_TEEN,
            ),
        )
    }

    @Test
    fun `non adjacent age band is penalized`() {
        assertEquals(
            -10,
            useCase.ageBandMatchScore(
                BookCharacterProfile.VOICE_AGE_CHILD,
                BookCharacterProfile.VOICE_AGE_ELDERLY,
            ),
        )
        assertEquals(
            -10,
            useCase.ageBandMatchScore(
                BookCharacterProfile.VOICE_AGE_ELDERLY,
                BookCharacterProfile.VOICE_AGE_TEEN,
            ),
        )
    }

    @Test
    fun `unknown on either side falls back to zero so keyword matching takes over`() {
        assertEquals(
            0,
            useCase.ageBandMatchScore(
                BookCharacterProfile.VOICE_AGE_UNKNOWN,
                BookCharacterProfile.VOICE_AGE_YOUNG_ADULT,
            ),
        )
        assertEquals(
            0,
            useCase.ageBandMatchScore(
                BookCharacterProfile.VOICE_AGE_YOUNG_ADULT,
                BookCharacterProfile.VOICE_AGE_UNKNOWN,
            ),
        )
        assertEquals(
            0,
            useCase.ageBandMatchScore(null, BookCharacterProfile.VOICE_AGE_YOUNG_ADULT),
        )
        assertEquals(
            0,
            useCase.ageBandMatchScore("", BookCharacterProfile.VOICE_AGE_YOUNG_ADULT),
        )
        assertEquals(
            0,
            useCase.ageBandMatchScore(BookCharacterProfile.VOICE_AGE_UNKNOWN, ""),
        )
    }

    @Test
    fun `a pooled character is assigned from its pool even with unknown gender`() = runBlocking {
        // AI 建的草稿卡常见「性别 unknown 但池是明确的」：以前性别一栏推不出就静默
        // 不分配，现在池本身就是明确指定的分组，池内照挑
        val gateway = FakeReadAloudVoiceGateway(listOf(voice("voice-a"), voice("voice-b")))
        val bindings = AutoAssignVoiceBindingsUseCase(gateway)(
            bookUrl = "book",
            performances = listOf(
                performance(characterId = "c-1", voicePoolLabel = "男青年"),
            ),
            poolVoiceIdsOf = { label -> if (label == "男青年") listOf("voice-a") else emptyList() },
            now = 100,
        )

        assertEquals(listOf("voice-a"), bindings.map { it.voiceId })
        assertEquals(0.85f, bindings.single().confidence)
        assertEquals("voice-a", gateway.upsertedBindings.single().voiceId)
    }

    @Test
    fun `an empty pool skips assignment instead of reaching outside the pool`() = runBlocking {
        // 指了池但池内没有启用音色：与池侧 ensureVoice 同口径，不从池外捞——
        // 否则配音页显示池内无音色、耳朵里却是池外的声音
        val gateway = FakeReadAloudVoiceGateway(listOf(voice("voice-a")))
        val bindings = AutoAssignVoiceBindingsUseCase(gateway)(
            bookUrl = "book",
            performances = listOf(
                performance(characterId = "c-1", voicePoolLabel = "空池"),
            ),
            poolVoiceIdsOf = { emptyList() },
            now = 100,
        )

        assertTrue(bindings.isEmpty())
        assertTrue(gateway.upsertedBindings.isEmpty())
    }

    @Test
    fun `a genderless character without a pool is still skipped`() = runBlocking {
        // 没指池且性别推不出：维持原行为，交给性别兜底链
        val gateway = FakeReadAloudVoiceGateway(listOf(voice("voice-a")))
        val bindings = AutoAssignVoiceBindingsUseCase(gateway)(
            bookUrl = "book",
            performances = listOf(
                performance(characterId = "c-1", voicePoolLabel = ""),
            ),
            poolVoiceIdsOf = { emptyList() },
            now = 100,
        )

        assertTrue(bindings.isEmpty())
        assertTrue(gateway.upsertedBindings.isEmpty())
    }

    @Test
    fun `an unpooled character keeps gender based assignment`() = runBlocking {
        // 没指池的走老路：性别分组 + 打分；角色是青年女，两个女声里年龄段
        // 精确匹配的那条（voice-f2）胜出，这是结构化匹配的既有语义
        val gateway = FakeReadAloudVoiceGateway(
            listOf(
                voice("voice-f", name = "阿姨（女）"),
                voice("voice-f2", name = "甜甜（女）"),
            ),
        )
        val bindings = AutoAssignVoiceBindingsUseCase(gateway)(
            bookUrl = "book",
            performances = listOf(
                performance(
                    characterId = "c-1",
                    voiceGender = BookCharacterProfile.VOICE_GENDER_FEMALE,
                    voicePoolLabel = "",
                ).copy(voiceAgeBand = BookCharacterProfile.VOICE_AGE_YOUNG_ADULT),
            ),
            voiceClassification = mapOf(
                "voice-f" to ("female" to BookCharacterProfile.VOICE_AGE_ELDERLY),
                "voice-f2" to ("female" to BookCharacterProfile.VOICE_AGE_YOUNG_ADULT),
            ),
            poolVoiceIdsOf = { emptyList() },
            now = 100,
        )

        assertEquals(listOf("voice-f2"), bindings.map { it.voiceId })
    }

    private fun voice(id: String, name: String = id) = ReadAloudVoice(
        id = id,
        engineType = ReadAloudVoice.ENGINE_SYSTEM,
        engineId = "system",
        speakerId = "",
        displayName = name,
    )

    private fun performance(
        characterId: String,
        voiceGender: String = BookCharacterProfile.VOICE_GENDER_UNKNOWN,
        voicePoolLabel: String = "",
    ) = CharacterPerformanceProfile(
        characterId = characterId,
        voiceGender = voiceGender,
        voiceAgeBand = BookCharacterProfile.VOICE_AGE_UNKNOWN,
        voicePoolLabel = voicePoolLabel,
    )
}

/**
 * 可配置的假音色网关：`ageBandMatchScore` 是纯函数不碰它；主流程用例靠 [voices] 喂数据、
 * 从 [upsertedBindings] 收结果。
 */
private class FakeReadAloudVoiceGateway(
    private val voices: List<ReadAloudVoice> = emptyList(),
) : ReadAloudVoiceGateway {
    val upsertedBindings = mutableListOf<BookVoiceBinding>()
    override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    override suspend fun countExcludedVoices(): Int = 0
    override fun observeVoices(): Flow<List<ReadAloudVoice>> = emptyFlow()
    override suspend fun getVoices(): List<ReadAloudVoice> = voices
    override suspend fun getEnabledVoices(): List<ReadAloudVoice> = voices
    override suspend fun getVoice(id: String): ReadAloudVoice? = voices.firstOrNull { it.id == id }
    override suspend fun upsertVoice(voice: ReadAloudVoice) = Unit
    override suspend fun deleteVoice(voice: ReadAloudVoice) = Unit
    override fun observeBindings(bookUrl: String): Flow<List<BookVoiceBinding>> = emptyFlow()
    override suspend fun getBindings(bookUrl: String): List<BookVoiceBinding> = emptyList()
    override suspend fun getBinding(
        bookUrl: String,
        subjectType: String,
        subjectId: String,
    ): BookVoiceBinding? = null
    override suspend fun upsertBinding(binding: BookVoiceBinding) {
        upsertedBindings += binding
    }
    override suspend fun deleteBinding(binding: BookVoiceBinding) = Unit
}
