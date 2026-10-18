package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.data.entities.CastCharacter
import io.legado.app.domain.gateway.CastSpeechGateway
import io.legado.app.help.readaloud.cast.CastSubjectResolver
import io.legado.app.help.readaloud.cast.CastVoicePicker
import io.legado.app.help.readaloud.cast.RegexCastEffect
import io.legado.app.help.readaloud.cast.RegexCastRuleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [CastSpeechGateway] 的 Room 实现。
 *
 * 这一层的规则很直白：接口方法都是纯读或幂等写，DAO 与 [RegexCastRuleStore] 的细节全部
 * 关在这里，覆盖层拿到的只有它真正需要的三样东西。
 */
class CastSpeechRepository : CastSpeechGateway {

    override suspend fun regexEffects(bookUrl: String): List<RegexCastEffect> =
        withContext(Dispatchers.IO) {
            appDb.bookDao.getBook(bookUrl)
                ?.let { RegexCastRuleStore.effectsFor(it) }
                .orEmpty()
        }

    override suspend fun quoteVoiceEffects(
        bookUrl: String,
        chapterIndex: Int,
    ): Map<Int, String> = withContext(Dispatchers.IO) {
        appDb.chapterRoleAssignmentDao.getForChapter(bookUrl, chapterIndex)
            .filter { it.voiceEffect.isNotBlank() }
            .associate { it.quoteOrdinal to it.voiceEffect }
    }

    override suspend fun characters(bookUrl: String): List<CastCharacter> =
        withContext(Dispatchers.IO) { appDb.castCharacterDao.getByBook(bookUrl) }

    override suspend fun subjectIdsFor(bookUrl: String, name: String): List<String> =
        withContext(Dispatchers.IO) { CastSubjectResolver.subjectIdsFor(bookUrl, name) }

    override suspend fun ensureCharacterVoice(character: CastCharacter): CastCharacter =
        CastVoicePicker.ensureVoice(character)
}