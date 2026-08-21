package io.legado.app.help.readaloud.playback

import io.legado.app.data.entities.HttpTTS
import io.legado.app.domain.model.readaloud.HttpTtsVoice
import io.legado.app.exception.NoStackTraceException
import io.legado.app.model.analyzeRule.AnalyzeUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Response
import java.io.File

/**
 * 把一段文本用 HttpTTS 引擎合成到文件，供试听使用。
 *
 * 走的是和朗读完全相同的 [AnalyzeUrl] 路径，所以脚本里的 `voice`/`emotion` 行为一致；
 * 但试听不需要重试，失败直接抛错让界面显示原因。
 */
object HttpTtsFileSynthesizer {

    suspend fun synthesize(
        httpTts: HttpTTS,
        text: String,
        output: File,
        speechRate: Int,
        voice: HttpTtsVoice? = null,
        emotion: String = "",
    ): Boolean = withContext(Dispatchers.IO) {
        val analyzeUrl = AnalyzeUrl(
            httpTts.url,
            speakText = text,
            speakSpeed = speechRate,
            speakVoice = voice,
            speakEmotion = emotion,
            source = httpTts,
            readTimeout = 60 * 1000L,
            coroutineContext = currentCoroutineContext(),
        )
        var response = analyzeUrl.getResponseAwait()
        currentCoroutineContext().ensureActive()
        httpTts.loginCheckJs?.takeIf { it.isNotBlank() }?.let { checkJs ->
            response = analyzeUrl.evalJS(checkJs, response) as Response
        }
        response.headers["Content-Type"]?.substringBefore(";")?.let { contentType ->
            if (contentType == "application/json" || contentType.startsWith("text/")) {
                throw NoStackTraceException(response.body.string())
            }
            httpTts.contentType?.takeIf { it.isNotBlank() }?.let { expected ->
                if (!contentType.matches(expected.toRegex())) {
                    throw NoStackTraceException("TTS服务器返回错误：" + response.body.string())
                }
            }
        }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()
        response.body.byteStream().use { input ->
            output.outputStream().use { out -> input.copyTo(out) }
        }
        output.length() > 0
    }
}
