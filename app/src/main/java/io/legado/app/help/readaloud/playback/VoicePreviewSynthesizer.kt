package io.legado.app.help.readaloud.playback

import io.legado.app.constant.AppLog
import io.legado.app.data.repository.HttpTtsRepository
import io.legado.app.domain.gateway.CloudTtsEngineGateway
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SystemTtsVoiceConfig
import io.legado.app.help.readaloud.HttpTtsVoiceCatalog
import io.legado.app.utils.GSON
import splitties.init.appCtx
import java.io.File

/**
 * 试听音色表里的一条音色。
 *
 * 三种引擎各有自己的合成器，这里只按 engineType 分派，保证试听和真正朗读走同一条合成路径。
 */
class VoicePreviewSynthesizer(
    private val httpTtsRepository: HttpTtsRepository,
    cloudEngineGateway: CloudTtsEngineGateway,
) {
    private val cloudSynthesizer = CloudTtsAudioSynthesizer(cloudEngineGateway)

    /** 懒加载：不试听系统音色就不去起 TextToSpeech 引擎 */
    private val systemSynthesizer by lazy { SystemTtsFileSynthesizer(appCtx) }

    suspend fun synthesize(voice: ReadAloudVoice, text: String, output: File): Boolean {
        return when (voice.engineType) {
            ReadAloudVoice.ENGINE_SYSTEM -> {
                val config = runCatching {
                    GSON.fromJson(voice.traitsJson, SystemTtsVoiceConfig::class.java)
                }.getOrNull()
                systemSynthesizer.synthesize(
                    engine = voice.engineId,
                    voiceName = voice.speakerId,
                    text = text,
                    output = output,
                    speechRate = config?.speechRate ?: 1f,
                    pitch = config?.pitch ?: 1f,
                )
            }

            ReadAloudVoice.ENGINE_HTTP -> {
                val httpTts = voice.engineId.toLongOrNull()?.let { httpTtsRepository.findById(it) }
                if (httpTts == null) {
                    false
                } else {
                    val httpVoice = HttpTtsVoiceCatalog.fromVoice(voice)
                    // 试听「每个音色听起来都一样」时，先看这条：httpVoice=null 或 id 相同就是音色没传下去
                    AppLog.putDebug(
                        "试听 http 音色 engine=${httpTts.name} speakerId=${voice.speakerId} " +
                            "解析出的 voice=${httpVoice?.id ?: "null"}"
                    )
                    HttpTtsFileSynthesizer.synthesize(
                        httpTts = httpTts,
                        text = text,
                        output = output,
                        speechRate = PREVIEW_SPEECH_RATE,
                        voice = httpVoice,
                    )
                }
            }

            ReadAloudVoice.ENGINE_CLOUD -> cloudSynthesizer.synthesize(voice, text, output)

            else -> false
        }
    }
}

/** 脚本 `speakSpeed` 的正常语速 */
private const val PREVIEW_SPEECH_RATE = 10
