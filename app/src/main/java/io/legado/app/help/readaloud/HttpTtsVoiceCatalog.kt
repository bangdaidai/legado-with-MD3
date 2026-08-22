package io.legado.app.help.readaloud

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.data.entities.HttpTTS
import io.legado.app.domain.model.readaloud.HttpTtsVoice
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.VoiceCatalogEntry
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * 从 HttpTTS 的 jsLib 里取发音人目录。
 *
 * jsLib 定义了 `voices()` 时一条引擎可以带多个音色；没定义就返回空，
 * 调用方回落到「一条引擎一个默认音色」的老行为。
 *
 * 会同步执行脚本，调用方自己保证不在主线程。
 */
object HttpTtsVoiceCatalog {

    private const val VOICES_JS =
        "typeof voices === 'function' ? JSON.stringify(voices({}, {})) : ''"

    fun getVoices(httpTts: HttpTTS): List<HttpTtsVoice> {
        if (httpTts.jsLib.isNullOrBlank()) return emptyList()
        val json = runCatching { httpTts.evalJS(VOICES_JS)?.toString() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val array = runCatching { JsonParser.parseString(json) as? JsonArray }
            .getOrNull() ?: return emptyList()
        return array.mapNotNull { (it as? JsonObject)?.toVoice() }
            .distinctBy(HttpTtsVoice::id)
    }

    /**
     * 音色表条目：定义了 `voices()` 的引擎展开成多条，没定义的保持「一条引擎一个音色」。
     *
     * 同样会同步执行脚本，调用方自己保证不在主线程。
     */
    fun catalogEntries(httpTtsList: List<HttpTTS>): List<VoiceCatalogEntry> =
        httpTtsList.flatMap { httpTts ->
            val voices = getVoices(httpTts)
            if (voices.isEmpty()) {
                listOf(
                    VoiceCatalogEntry(
                        engineType = ReadAloudVoice.ENGINE_HTTP,
                        engineId = httpTts.id.toString(),
                        displayName = httpTts.name,
                        sourceRevision = httpTts.lastUpdateTime,
                    )
                )
            } else {
                voices.map { voice ->
                    VoiceCatalogEntry(
                        engineType = ReadAloudVoice.ENGINE_HTTP,
                        engineId = httpTts.id.toString(),
                        speakerId = voice.id,
                        displayName = "${httpTts.name} · ${voice.name}",
                        traitsJson = GSON.toJson(voice),
                        sourceRevision = httpTts.lastUpdateTime,
                    )
                }
            }
        }

    /** 运行时把音色表里的 HTTP 音色还原成朗读 URL JS 里 `voice` 的形状。 */
    fun fromVoice(voice: ReadAloudVoice): HttpTtsVoice? {
        if (voice.speakerId.isBlank()) return null
        // traitsJson 可能是老版本混淆包写下的（字段名被 R8 改过），解不出来就只用 id/name 兜底
        return runCatching {
            GSON.fromJsonObject<HttpTtsVoice>(voice.traitsJson).getOrNull()
                ?.takeIf { it.id.isNotBlank() }
        }.getOrNull()
            ?: HttpTtsVoice(id = voice.speakerId, name = voice.displayName)
    }

    private fun JsonObject.toVoice(): HttpTtsVoice? {
        val id = string("id")?.takeIf(String::isNotBlank) ?: return null
        return HttpTtsVoice(
            id = id,
            name = string("name")?.takeIf(String::isNotBlank) ?: id,
            language = string("language").orEmpty(),
            gender = string("gender").orEmpty(),
            style = string("style").orEmpty(),
            tags = (get("tags") as? JsonArray)
                ?.filter { it.isJsonPrimitive }
                ?.map { it.asString }
                .orEmpty(),
            extraJson = (get("extra") as? JsonObject)?.toString().orEmpty(),
        )
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
