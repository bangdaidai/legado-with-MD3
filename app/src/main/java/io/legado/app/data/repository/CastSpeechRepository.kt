package io.legado.app.data.repository

import io.legado.app.data.appDb
import io.legado.app.domain.gateway.CastSpeechGateway
import io.legado.app.help.readaloud.cast.RegexCastEffect
import io.legado.app.help.readaloud.cast.RegexCastRuleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [CastSpeechGateway] 的 Room 实现。
 *
 * 这一层的规则很直白：接口方法都是纯读，DAO 与 [RegexCastRuleStore] 的细节全部
 * 关在这里，效果层拿到的只有它真正需要的两样东西。
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
}