package io.legado.app.domain.usecase

import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.BookVoiceBinding
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
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
}

/**
 * 只为满足接口而存在的空实现：`ageBandMatchScore` 是纯函数，不触碰 gateway。
 */
private class FakeReadAloudVoiceGateway : ReadAloudVoiceGateway {
    override suspend fun <T> withTransaction(block: suspend () -> T): T = block()
    override suspend fun countExcludedVoices(): Int = 0
    override fun observeVoices(): Flow<List<ReadAloudVoice>> = emptyFlow()
    override suspend fun getVoices(): List<ReadAloudVoice> = emptyList()
    override suspend fun getEnabledVoices(): List<ReadAloudVoice> = emptyList()
    override suspend fun getVoice(id: String): ReadAloudVoice? = null
    override suspend fun upsertVoice(voice: ReadAloudVoice) = Unit
    override suspend fun deleteVoice(voice: ReadAloudVoice) = Unit
    override fun observeBindings(bookUrl: String): Flow<List<BookVoiceBinding>> = emptyFlow()
    override suspend fun getBindings(bookUrl: String): List<BookVoiceBinding> = emptyList()
    override suspend fun getBinding(
        bookUrl: String,
        subjectType: String,
        subjectId: String,
    ): BookVoiceBinding? = null
    override suspend fun upsertBinding(binding: BookVoiceBinding) = Unit
    override suspend fun deleteBinding(binding: BookVoiceBinding) = Unit
}
