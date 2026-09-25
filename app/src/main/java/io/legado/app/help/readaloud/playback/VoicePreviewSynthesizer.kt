package io.legado.app.help.readaloud.playback

import io.legado.app.constant.AppLog
import io.legado.app.data.repository.HttpTtsRepository
import io.legado.app.data.repository.ai.AiLogRepository
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
    private val cloudEngineGateway: CloudTtsEngineGateway,
    private val aiLogRepository: AiLogRepository,
) {
    private val cloudSynthesizer = CloudTtsAudioSynthesizer(cloudEngineGateway, aiLogRepository)

    /** 懒加载：不试听系统音色就不去起 TextToSpeech 引擎 */
    private val systemSynthesizer by lazy { SystemTtsFileSynthesizer(appCtx) }

    suspend fun synthesize(voice: ReadAloudVoice, text: String, output: File): Boolean {
        val error = synthesizeOrNull(voice, text, output)
        if (error != null) {
            // 各分支原来都是静默 return false，日志里查不到任何痕迹。
            // 失败必须留下一行原因，否则用户只能看到一句「试听失败」。
            AppLog.put(
                "试听失败：$error（engineType=${voice.engineType} " +
                    "engineId=${voice.engineId} speakerId=${voice.speakerId}）"
            )
        }
        return error == null
    }

    /** 成功返回 null；失败返回可直接进日志的中文原因 */
    private suspend fun synthesizeOrNull(
        voice: ReadAloudVoice,
        text: String,
        output: File,
    ): String? = when (voice.engineType) {
        ReadAloudVoice.ENGINE_SYSTEM -> {
            val config = runCatching {
                GSON.fromJson(voice.traitsJson, SystemTtsVoiceConfig::class.java)
            }.getOrNull()
            val ok = systemSynthesizer.synthesize(
                engine = voice.engineId,
                voiceName = voice.speakerId,
                text = text,
                output = output,
                speechRate = config?.speechRate ?: 1f,
                pitch = config?.pitch ?: 1f,
            )
            if (ok) {
                null
            } else {
                "系统 TTS 未产出音频：设备可能未安装/未启用系统语音引擎" +
                    if (voice.engineId.isBlank()) {
                        "（当前用系统默认引擎）"
                    } else {
                        "（引擎=${voice.engineId}）"
                    }
            }
        }

        ReadAloudVoice.ENGINE_HTTP -> {
            val httpTts = voice.engineId.toLongOrNull()?.let { httpTtsRepository.findById(it) }
            if (httpTts == null) {
                "找不到对应的本地/HTTP TTS 服务（engineId=${voice.engineId}），可能已被删除或不是服务主键"
            } else {
                val httpVoice = HttpTtsVoiceCatalog.fromVoice(voice)
                // 试听「每个音色听起来都一样」时，先看这条：httpVoice=null 或 id 相同就是音色没传下去
                AppLog.putDebug(
                    "试听 http 音色 engine=${httpTts.name} speakerId=${voice.speakerId} " +
                        "解析出的 voice=${httpVoice?.id ?: "null"}"
                )
                val ok = HttpTtsFileSynthesizer.synthesize(
                    httpTts = httpTts,
                    text = text,
                    output = output,
                    speechRate = PREVIEW_SPEECH_RATE,
                    voice = httpVoice,
                )
                if (ok) null else "TTS 服务「${httpTts.name}」合成失败，检查服务地址是否可用"
            }
        }

        ReadAloudVoice.ENGINE_CLOUD -> {
            val engine = cloudEngineGateway.get(voice.engineId)
            when {
                engine == null -> "云 TTS 引擎不存在（id=${voice.engineId}），可能已被删除"
                !engine.enabled -> "云 TTS 引擎「${engine.name}」已停用"
                else -> {
                    val ok = cloudSynthesizer.synthesize(voice, text, output)
                    if (ok) null else "云引擎「${engine.name}」合成失败，具体原因见 AI 调用记录里的 cloudTts 条目"
                }
            }
        }

        else -> "未知引擎类型 ${voice.engineType}"
    }
}

/** 脚本 `speakSpeed` 的正常语速 */
private const val PREVIEW_SPEECH_RATE = 10
