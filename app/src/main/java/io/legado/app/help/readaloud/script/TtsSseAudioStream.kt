package io.legado.app.help.readaloud.script

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import io.legado.app.exception.NoStackTraceException
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSource
import okio.ByteString.Companion.decodeBase64
import okio.buffer
import okio.source
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 脚本引擎的流式音频支持（SSE / 分片），移植自 legado_NG：
 * - SSE 事件行聚合 + textRules 提取音频分片；
 * - PCM 分片输出前注入 44 字节流式 WAV 头，让播放器在总长未知时也能开始解码。
 */
internal data class TtsPcmConfig(
    val sampleRate: Int,
    val channels: Int = 1,
    val bitsPerSample: Int = 16
) {
    fun wavHeader(): ByteArray {
        val safeSampleRate = sampleRate.coerceIn(8_000, 384_000)
        val safeChannels = channels.coerceIn(1, 8)
        val safeBits = bitsPerSample.takeIf { it in setOf(8, 16, 24, 32) } ?: 16
        val blockAlign = safeChannels * safeBits / 8
        val byteRate = safeSampleRate * blockAlign
        val streamingDataSize = Int.MAX_VALUE - 44
        return ByteArray(44).apply {
            putAscii(0, "RIFF")
            putLittleEndianInt(4, streamingDataSize + 36)
            putAscii(8, "WAVE")
            putAscii(12, "fmt ")
            putLittleEndianInt(16, 16)
            putLittleEndianShort(20, 1)
            putLittleEndianShort(22, safeChannels)
            putLittleEndianInt(24, safeSampleRate)
            putLittleEndianInt(28, byteRate)
            putLittleEndianShort(32, blockAlign)
            putLittleEndianShort(34, safeBits)
            putAscii(36, "data")
            putLittleEndianInt(40, streamingDataSize)
        }
    }
}

internal data class TtsWebSocketConfig(
    val openMessages: List<String> = emptyList(),
    val textRules: List<TtsWebSocketTextRule> = emptyList(),
    val binaryAudio: Boolean = true,
    val finishOnClose: Boolean = false,
    val connectTimeoutSeconds: Int = 15,
    val firstAudioTimeoutSeconds: Int = 15,
    val idleTimeoutSeconds: Int = 15,
    val finishGraceMillis: Int = 0,
    val maxAudioBytes: Int = DEFAULT_MAX_AUDIO_BYTES,
    val pcm: TtsPcmConfig? = null
) {
    companion object {
        const val DEFAULT_MAX_AUDIO_BYTES = 32 * 1024 * 1024
    }
}

internal data class TtsWebSocketTextRule(
    val matchPath: String,
    val equalsValue: String? = null,
    val notEqualsValue: String? = null,
    val sendMessages: List<String> = emptyList(),
    val audioPath: String? = null,
    val audioEncoding: String? = null,
    val finish: Boolean = false,
    val errorPath: String? = null
)

internal data class TtsWebSocketTextResult(
    val sendMessages: List<String> = emptyList(),
    val audioChunks: List<ByteArray> = emptyList(),
    val finished: Boolean = false,
    val error: String? = null
)

/** textRules 状态机：按 matchPath 匹配 JSON 事件，产出要发送的消息与音频分片。 */
internal class TtsWebSocketProtocol(
    private val config: TtsWebSocketConfig
) {

    fun openMessages(): List<String> = config.openMessages

    fun binaryAudio(bytes: ByteArray): ByteArray? {
        return bytes.takeIf { config.binaryAudio && it.isNotEmpty() }
    }

    fun onText(message: String): TtsWebSocketTextResult {
        val root = runCatching { JsonParser.parseString(message) }.getOrNull()
            ?: return TtsWebSocketTextResult()
        val sendMessages = mutableListOf<String>()
        val audioChunks = mutableListOf<ByteArray>()
        var finished = false
        var error: String? = null
        config.textRules.forEach { rule ->
            val matchValue = root.elementAtPath(rule.matchPath)?.primitiveText()
            val matches = when {
                rule.equalsValue != null -> matchValue == rule.equalsValue
                rule.notEqualsValue != null -> matchValue != null && matchValue != rule.notEqualsValue
                else -> matchValue != null
            }
            if (!matches) return@forEach
            sendMessages += rule.sendMessages
            rule.audioPath?.let { path ->
                root.elementAtPath(path)?.primitiveText()?.let { audioValue ->
                    val bytes = when (rule.audioEncoding.orEmpty().lowercase()) {
                        "", "base64" -> audioValue.decodeBase64Bytes()
                        "text", "utf8", "utf-8" -> audioValue.toByteArray(Charsets.UTF_8)
                        else -> null
                    }
                    if (bytes != null && bytes.isNotEmpty()) audioChunks.add(bytes)
                }
            }
            if (rule.errorPath != null) {
                error = root.elementAtPath(rule.errorPath)?.primitiveText()
                    ?.takeIf { it.isNotBlank() }
                    ?: "脚本 TTS 服务返回错误"
            }
            finished = finished || rule.finish
        }
        return TtsWebSocketTextResult(
            sendMessages = sendMessages,
            audioChunks = audioChunks,
            finished = finished,
            error = error
        )
    }
}

internal data class TtsSseConfig(
    val dataPrefix: String = "data:",
    val doneData: String = "[DONE]",
    val finishOnEof: Boolean = true,
    val textRules: List<TtsWebSocketTextRule> = emptyList(),
    val maxAudioBytes: Int = TtsWebSocketConfig.DEFAULT_MAX_AUDIO_BYTES,
    val pcm: TtsPcmConfig? = null
)

/** 跨线程音频分片队列，供 WebSocket 会话协程写入、播放读取线程消费。 */
internal class TtsChunkedInputStream(
    prefix: ByteArray = byteArrayOf(),
    private val onCloseStream: () -> Unit = {}
) : InputStream() {

    private sealed interface Event {
        data class Chunk(val bytes: ByteArray) : Event
        data class Error(val error: IOException) : Event
        data object End : Event
    }

    private val events = LinkedBlockingQueue<Event>()
    private val terminal = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private var current = prefix
    private var currentOffset = 0

    fun offer(bytes: ByteArray) {
        if (bytes.isNotEmpty() && !terminal.get() && !closed.get()) {
            events.offer(Event.Chunk(bytes))
        }
    }

    fun finish() {
        if (terminal.compareAndSet(false, true)) {
            events.offer(Event.End)
        }
    }

    fun fail(error: Throwable) {
        if (terminal.compareAndSet(false, true)) {
            events.offer(Event.Error(error.asIOException()))
        }
    }

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (closed.get()) return -1
        while (currentOffset >= current.size) {
            when (val event = takeEvent()) {
                is Event.Chunk -> {
                    current = event.bytes
                    currentOffset = 0
                }
                is Event.Error -> throw event.error
                Event.End -> return -1
            }
        }
        val count = minOf(length, current.size - currentOffset)
        current.copyInto(buffer, offset, currentOffset, currentOffset + count)
        currentOffset += count
        return count
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            terminal.set(true)
            events.clear()
            events.offer(Event.End)
            onCloseStream()
        }
    }

    private fun takeEvent(): Event {
        return try {
            events.take()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("TTS 音频流读取被中断", e)
        }
    }
}

internal class TtsStreamingResponseBody(
    private val mediaType: MediaType?,
    inputStream: InputStream
) : ResponseBody() {
    private val bufferedSource by lazy { inputStream.source().buffer() }

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = -1L

    override fun source(): BufferedSource = bufferedSource
}

/** 把 OkHttp 的 SSE 响应体转换成音频流响应体。 */
internal object TtsSseEngineClient {

    fun transform(
        response: Response,
        request: TtsScriptEngineClient.TtsScriptRequest
    ): Response {
        val config = request.sseConfig
            ?: throw NoStackTraceException("SSE 合成请求缺少 stream 配置")
        val contentType = if (config.pcm != null) {
            "audio/wav".toMediaTypeOrNull()
        } else {
            request.audioContentType?.toMediaTypeOrNull()
        }
        val input = TtsSseAudioInputStream(response.body.source(), config)
        return response.newBuilder()
            .header("Content-Type", contentType?.toString() ?: "application/octet-stream")
            .body(TtsStreamingResponseBody(contentType, input))
            .build()
    }
}

internal class TtsSseAudioInputStream(
    private val source: BufferedSource,
    private val config: TtsSseConfig
) : InputStream() {

    private val protocol = TtsWebSocketProtocol(
        TtsWebSocketConfig(textRules = config.textRules)
    )
    private val chunks = ArrayDeque<ByteArray>()
    private var current = config.pcm?.wavHeader() ?: byteArrayOf()
    private var currentOffset = 0
    private var audioBytes = 0L
    private var finished = false

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (currentOffset >= current.size) {
            current = nextChunk() ?: return -1
            currentOffset = 0
        }
        val count = minOf(length, current.size - currentOffset)
        current.copyInto(buffer, offset, currentOffset, currentOffset + count)
        currentOffset += count
        return count
    }

    override fun close() {
        finished = true
        source.close()
    }

    private fun nextChunk(): ByteArray? {
        chunks.removeFirstOrNull()?.let { return it }
        while (!finished) {
            val data = readEventData()
            if (data == null) {
                finished = true
                if (!config.finishOnEof) {
                    throw IOException("SSE TTS 连接在完成事件前关闭")
                }
                return null
            }
            if (data.trim() == config.doneData) {
                finished = true
                return null
            }
            val result = protocol.onText(data)
            result.error?.let { throw IOException(it) }
            result.audioChunks.forEach { addChunk(it) }
            if (result.finished) finished = true
            chunks.removeFirstOrNull()?.let { return it }
        }
        return null
    }

    private fun readEventData(): String? {
        val dataLines = mutableListOf<String>()
        while (true) {
            val line = source.readUtf8Line()
            if (line == null) {
                return dataLines.takeIf { it.isNotEmpty() }?.joinToString("\n")
            }
            if (line.isEmpty()) {
                if (dataLines.isNotEmpty()) return dataLines.joinToString("\n")
                continue
            }
            if (line.startsWith(":")) continue
            if (line.startsWith(config.dataPrefix)) {
                dataLines += line.removePrefix(config.dataPrefix).removePrefix(" ")
            }
        }
    }

    private fun addChunk(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        audioBytes += bytes.size
        if (audioBytes > config.maxAudioBytes.safeMaxAudioBytes()) {
            throw IOException("SSE TTS 音频超过大小限制")
        }
        chunks.addLast(bytes)
    }
}

internal fun Int.safeMaxAudioBytes(): Long {
    return takeIf { it > 0 }
        ?.coerceAtMost(128 * 1024 * 1024)
        ?.toLong()
        ?: TtsWebSocketConfig.DEFAULT_MAX_AUDIO_BYTES.toLong()
}

internal fun String.decodeBase64Bytes(): ByteArray? =
    runCatching { decodeBase64()?.toByteArray() }.getOrNull()

private fun Throwable.asIOException(): IOException {
    return this as? IOException ?: IOException(message ?: javaClass.simpleName, this)
}

private fun ByteArray.putAscii(offset: Int, value: String) {
    value.forEachIndexed { index, char -> this[offset + index] = char.code.toByte() }
}

private fun ByteArray.putLittleEndianShort(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}

private fun ByteArray.putLittleEndianInt(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
}

/** 取 JSON 路径（`a.b[0].c`）上的元素。 */
internal fun JsonElement.elementAtPath(path: String): JsonElement? {
    val tokens = path.trim()
        .removePrefix("$")
        .removePrefix(".")
        .split(".")
        .filter { it.isNotBlank() }
    var current: JsonElement = this
    tokens.forEach { token ->
        val name = token.substringBefore("[")
        if (name.isNotBlank()) {
            current = current.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.get(name)
                ?: return null
        }
        Regex("""\[(\d+)]""").findAll(token).forEach { match ->
            val index = match.groupValues[1].toIntOrNull() ?: return null
            val array = current.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            if (index !in 0 until array.size()) {
                return null
            }
            current = array[index]
        }
    }
    return current
}

internal fun JsonElement.primitiveText(): String? =
    takeIf { it.isJsonPrimitive }?.asString
