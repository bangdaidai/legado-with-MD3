package io.legado.app.help.readaloud.playback

import io.legado.app.data.repository.ai.AiLogEntry
import io.legado.app.data.repository.ai.AiLogRepository
import io.legado.app.domain.gateway.CloudTtsEngineGateway
import io.legado.app.domain.model.readaloud.CloudTtsSynthesisRequest
import io.legado.app.domain.model.readaloud.CloudTtsEngine
import io.legado.app.domain.model.readaloud.CloudTtsVoiceConfig
import io.legado.app.domain.model.readaloud.CloudTtsVoiceDescriptor
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.utils.GSON
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CloudTtsAudioSynthesizer(
    private val engineGateway: CloudTtsEngineGateway,
    private val aiLogRepository: AiLogRepository,
) {
    private val registry = CloudTtsProviderRegistry()

    suspend fun fetchVoices(engineId: String): List<CloudTtsVoiceDescriptor> =
        withContext(Dispatchers.IO) {
            val engine = engineGateway.get(engineId) ?: error("云 TTS 引擎不存在")
            require(engine.enabled) { "云 TTS 引擎已停用" }
            return@withContext logCloudTts(engine, "cloudTtsVoices", "拉取音色列表") {
                registry.get(engine.provider).fetchVoices(engine)
            }.getOrThrow()
        }

    suspend fun fetchVoices(engine: CloudTtsEngine): List<CloudTtsVoiceDescriptor> =
        withContext(Dispatchers.IO) {
            return@withContext logCloudTts(engine, "cloudTtsVoices", "拉取音色列表") {
                registry.get(engine.provider).fetchVoices(engine)
            }.getOrThrow()
        }

    suspend fun synthesize(
        engine: CloudTtsEngine,
        request: CloudTtsSynthesisRequest,
        output: File,
    ): Boolean = withContext(Dispatchers.IO) {
        val audio = logCloudTts(engine, "cloudTts", "语音合成 ${request.text.length} 字") {
            registry.get(engine.provider).synthesize(engine, request)
        }.getOrNull() ?: return@withContext false
        output.parentFile?.mkdirs()
        output.writeBytes(audio.bytes)
        output.length() > 0
    }

    suspend fun synthesize(
        voice: ReadAloudVoice,
        text: String,
        output: File,
        styleOverride: String = "",
        characterPerformance: CharacterPerformanceProfile? = null,
        roleType: SpeechRoleType = SpeechRoleType.Unknown,
    ): Boolean = withContext(Dispatchers.IO) {
        val engine = engineGateway.get(voice.engineId) ?: return@withContext false
        if (!engine.enabled) return@withContext false
        val config = runCatching {
            GSON.fromJson(voice.traitsJson, CloudTtsVoiceConfig::class.java)
        }.getOrNull() ?: CloudTtsVoiceConfig()
        val supportedStyles = runCatching {
            GSON.fromJson(voice.emotionCatalogJson, Array<String>::class.java).toSet()
        }.getOrDefault(emptySet())
        val emotionControl = if (config.automaticEmotion != false) {
            CloudTtsEmotionMapper.map(engine.provider, styleOverride)
        } else {
            CloudTtsEmotionControl()
        }
        val mappedStyle = emotionControl.style.takeIf { it in supportedStyles }
        val characterInstruction = if (config.characterPersonality != false) {
            CharacterPerformanceInstructionBuilder.build(engine.provider, characterPerformance)
        } else ""
        val roleInstruction = if (config.thoughtPerformance != false) {
            CloudTtsRoleInstructionMapper.map(engine.provider, roleType)
        } else ""
        val instructions = listOfNotNull(
            config.instructions.takeIf(String::isNotBlank),
            characterInstruction.takeIf(String::isNotBlank),
            roleInstruction.takeIf(String::isNotBlank),
            emotionControl.instruction.takeIf(String::isNotBlank),
        ).joinToString(" ")
        val audio = logCloudTts(engine, "cloudTts", "语音合成 ${text.length} 字") {
            registry.get(engine.provider).synthesize(
                engine = engine,
                request = CloudTtsSynthesisRequest(
                    text = text,
                    voiceId = voice.speakerId,
                    locale = config.locale,
                    style = mappedStyle ?: config.style,
                    role = config.role,
                    instructions = instructions,
                    speed = config.speed,
                    pitch = config.pitch,
                    volume = config.volume,
                    format = config.format,
                    sampleRate = config.sampleRate,
                ),
            )
        }.getOrNull() ?: return@withContext false
        output.parentFile?.mkdirs()
        output.writeBytes(audio.bytes)
        output.length() > 0
    }

    private suspend inline fun <T> logCloudTts(
        engine: CloudTtsEngine,
        kind: String,
        summary: String,
        block: () -> T,
    ): Result<T> {
        val start = System.currentTimeMillis()
        val result = runCatching(block)
        aiLogRepository.record(
            AiLogEntry(
                timeMillis = start,
                kind = kind,
                providerName = engine.provider.storageValue,
                modelId = engine.model.takeIf { it.isNotBlank() } ?: engine.name,
                summary = summary,
                success = result.isSuccess,
                durationMillis = System.currentTimeMillis() - start,
                error = result.exceptionOrNull()?.message,
            )
        )
        return result
    }
}
