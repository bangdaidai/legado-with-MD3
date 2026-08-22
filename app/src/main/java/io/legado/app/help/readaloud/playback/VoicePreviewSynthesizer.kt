package io.legado.app.help.readaloud.playback

import io.legado.app.data.repository.HttpTtsRepository
import io.legado.app.domain.gateway.CloudTtsEngineGateway
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SystemTtsVoiceConfig
import io.legado.app.help.readaloud.HttpTtsVoiceCatalog
import io.legado.app.utils.GSON
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

    suspend fun synthesize(voice: ReadAloudVoice, text: String, output: File): Boolean {
        return when (voice.engineType) {
            ReadAloudVoice.ENGINE_SYSTEM -> {
                val config = runCatching {
                    GSON.fromJson(voice.traitsJson, SystemTtsVoiceConfig::class.java)
                }.getOrNull()
                SystemTtsFileSynthesizer.synthesize(
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
                    HttpTtsFileSynthesizer.synthesize(
                        httpTts = httpTts,
                        text = text,
                        output = output,
                        speechRate = PREVIEW_SPEECH_RATE,
                        voice = HttpTtsVoiceCatalog.fromVoice(voice),
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
