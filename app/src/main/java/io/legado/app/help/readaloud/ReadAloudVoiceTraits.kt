package io.legado.app.help.readaloud

import io.legado.app.domain.model.readaloud.CloudTtsVoiceConfig
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * 音色的性别 / 语言 / 风格 / 描述标签。
 *
 * 音色表没有这些列，数据全塞在 [ReadAloudVoice.traitsJson] 里，而且三种引擎存的结构还不一样
 * （http 存 [io.legado.app.domain.model.readaloud.HttpTtsVoice]、云端存 [CloudTtsVoiceConfig]、
 * 系统 TTS 只存语速音高），所以统一在这里按 engineType 分派解析，
 * 供音色列表、人物配音、自动配音三处复用。
 */
data class VoiceTraits(
    val gender: String = ReadAloudVoiceTraits.GENDER_UNKNOWN,
    val language: String = "",
    val style: String = "",
    val tags: List<String> = emptyList(),
) {

    /** 展示用的描述标签，不含性别（性别要本地化，交给调用方翻） */
    val descriptors: List<String> by lazy {
        (listOf(style) + tags).map(String::trim).filter(String::isNotEmpty).distinct()
    }
}

object ReadAloudVoiceTraits {

    const val GENDER_MALE = "male"
    const val GENDER_FEMALE = "female"
    const val GENDER_UNKNOWN = "unknown"

    fun of(voice: ReadAloudVoice): VoiceTraits {
        val declared = when (voice.engineType) {
            ReadAloudVoice.ENGINE_HTTP -> HttpTtsVoiceCatalog.fromVoice(voice)?.let {
                VoiceTraits(
                    gender = normalizeGender(it.gender),
                    language = it.language,
                    style = it.style,
                    tags = it.tags,
                )
            }

            ReadAloudVoice.ENGINE_CLOUD -> runCatching {
                GSON.fromJsonObject<CloudTtsVoiceConfig>(voice.traitsJson).getOrNull()
            }.getOrNull()?.let {
                VoiceTraits(
                    gender = normalizeGender(it.gender),
                    language = it.locale,
                    style = it.style,
                    tags = listOf(it.role),
                )
            }

            else -> null
        } ?: VoiceTraits()
        // 系统音色和没声明性别的条目只能看名字：Azure/Edge 的中文音色是「晓=女、云=男」
        return if (declared.gender == GENDER_UNKNOWN) {
            declared.copy(gender = guessGender(voice.displayName))
        } else {
            declared
        }
    }

    fun normalizeGender(value: String?): String {
        val text = value?.trim()?.lowercase().orEmpty()
        if (text.isEmpty()) return GENDER_UNKNOWN
        // female 里含 male、woman 里含 man，必须先判女再判男
        return when {
            text.startsWith("f") || text.startsWith("w") || "女" in text -> GENDER_FEMALE
            text.startsWith("m") || "男" in text -> GENDER_MALE
            else -> GENDER_UNKNOWN
        }
    }

    private fun guessGender(displayName: String): String {
        val name = displayName.lowercase()
        return when {
            FEMALE_HINTS.any { it in name } -> GENDER_FEMALE
            MALE_HINTS.any { it in name } -> GENDER_MALE
            else -> GENDER_UNKNOWN
        }
    }

    private val FEMALE_HINTS =
        listOf("female", "woman", "girl", "女", "晓", "xiao", "姐", "妹", "娘")
    private val MALE_HINTS = listOf("male", "man", "boy", "男", "云", "yun", "哥", "叔", "爷")
}
