package io.legado.app.help.readaloud.playback

import io.legado.app.data.entities.HttpTTS
import io.legado.app.domain.model.readaloud.HttpTtsVoice
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.help.readaloud.HttpTtsVoiceCatalog
import io.legado.app.help.readaloud.script.TtsScriptEngineClient
import io.legado.app.help.readaloud.script.isScriptEngine
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * JS 朗读引擎合成器：协议调用在 [TtsScriptEngineClient]，
 * 这里只负责源级配额与关闭联动，与 [HttpTtsAudioSynthesizer] 对齐。
 * 脚本引擎不走流式播放，只产出文件；loginCheckJs 不适用（鉴权由脚本自理）。
 */
class ScriptTtsAudioSynthesizer {

    /** 文件合成入口（试听等场景复用）；[voice] 为音色目录里的音色，决定 voice 参数与风格。 */
    suspend fun synthesize(
        source: HttpTTS,
        text: String,
        output: File,
        voice: HttpTtsVoice? = null,
        emotion: String = "",
    ): Boolean = withContext(Dispatchers.IO) {
        output.parentFile?.mkdirs()
        try {
            openStream(source, text, null, voice, emotion).use { input ->
                output.outputStream().use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                    }
                }
            }
            output.length() > 0
        } catch (error: Throwable) {
            output.delete()
            currentCoroutineContext().ensureActive()
            throw error
        }
    }

    suspend fun openStream(
        source: HttpTTS,
        text: String,
        voice: ReadAloudVoice? = null,
        emotion: String = "",
        catalogVoice: HttpTtsVoice? = null,
    ): InputStream {
        require(voice == null ||
            (voice.engineType == ReadAloudVoice.ENGINE_HTTP && voice.engineId == source.id.toString())) {
            "HTTP TTS 音色与源不匹配"
        }
        val quota = HttpTtsSynthesisLimiter.shared.acquire(
            source.id.toString(), source.synthesisThreadCount,
        )
        try {
            currentCoroutineContext().ensureActive()
            val resolvedVoice = catalogVoice ?: voice?.let { HttpTtsVoiceCatalog.fromVoice(it) }
            val input = TtsScriptEngineClient.getSynthesisStream(
                source = source,
                text = text,
                voice = resolvedVoice,
                emotion = emotion,
                coroutineContext = currentCoroutineContext(),
            )
            return object : FilterInputStream(input) {
                private val closed = AtomicBoolean()
                override fun close() {
                    if (closed.compareAndSet(false, true)) try {
                        super.close()
                    } finally {
                        quota.close()
                    }
                }
                override fun read(): Int = consume { input.read() }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    consume { input.read(buffer, offset, length) }

                private inline fun consume(read: () -> Int): Int = try {
                    read().also { if (it < 0) close() }
                } catch (error: Throwable) {
                    close()
                    throw error
                }
            }
        } catch (error: Throwable) {
            quota.close()
            currentCoroutineContext().ensureActive()
            throw error
        }
    }
}
