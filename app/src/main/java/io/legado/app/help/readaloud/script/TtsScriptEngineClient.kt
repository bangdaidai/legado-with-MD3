package io.legado.app.help.readaloud.script

import androidx.collection.LruCache
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.script.CompiledScript
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.BuildConfig
import io.legado.app.data.entities.HttpTTS
import io.legado.app.domain.model.readaloud.HttpTtsVoice
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.getShareScope
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.jsSource.JsSourceCrypto
import io.legado.app.model.jsSource.JsSourceEngine
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString.Companion.decodeBase64
import splitties.init.appCtx
import java.io.InputStream
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * JS 朗读引擎执行客户端，协议与 legado_NG 一致：
 * 脚本实现 `options()` / `voices(options, ctx)` / `synthesize(text, voice, params, options, ctx)`，
 * synthesize 返回音频 URL 字符串或请求描述对象（支持 HTTP / JSON 提取 / SSE 流式）。
 */
object TtsScriptEngineClient {

    private const val DEFAULT_AUDIO_TYPE = "audio/mpeg"

    private val scriptCache = LruCache<String, CompiledScript>(16)

    private val optionsCache = object : LinkedHashMap<String, List<TtsScriptOption>>(
        8, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<TtsScriptOption>>?): Boolean =
            size > 8
    }

    fun defaultAudioType(source: HttpTTS): String =
        TtsScriptMetadata.parse(source.script).audioType.takeIf { it.isNotBlank() }
            ?: source.contentType?.takeIf { it.isNotBlank() }
            ?: DEFAULT_AUDIO_TYPE

    /** 脚本与配置的指纹：拼进合成缓存键，脚本或配置变更即失效旧缓存。 */
    fun cacheKeyPart(source: HttpTTS): String {
        val script = source.script.orEmpty()
        return "script:${MD5Utils.md5Encode16(script + source.scriptOptionsJson)}"
    }

    //region 脚本求值

    private fun compileScript(script: String): CompiledScript {
        scriptCache.get(script)?.let { return it }
        return RhinoScriptEngine.compile(script).also { scriptCache.put(script, it) }
    }

    private fun buildScope(
        source: HttpTTS,
        coroutineContext: CoroutineContext?,
    ): com.script.ScriptBindings {
        val bindings = buildScriptBindings { values ->
            values["java"] = source
            values["source"] = source
            values["sourceApi"] = source
            values["baseUrl"] = source.getKey()
            values["cookie"] = CookieStore
            values["cache"] = CacheManager
        }
        val sharedScope = source.getShareScope(coroutineContext)
        return if (sharedScope == null) {
            bindings.apply { prototype = JsSourceCrypto.cryptoScope(coroutineContext) }
        } else {
            bindings.apply { prototype = sharedScope }
        }
    }

    /**
     * 调用脚本函数：主脚本先在全新作用域求值（编译结果按脚本文本缓存），
     * 再执行带 JSON 字面量实参的 IIFE 包装，非字符串返回值统一 JSON.stringify。
     */
    private fun callEngineFunction(
        source: HttpTTS,
        functionName: String,
        argumentLiterals: List<String> = emptyList(),
        coroutineContext: CoroutineContext? = null,
    ): String? {
        val script = source.script?.takeIf { it.isNotBlank() } ?: return null
        val scope = buildScope(source, coroutineContext)
        compileScript(script).eval(scope, coroutineContext)
        val argumentList = argumentLiterals.joinToString(", ")
        val js = """
            (function() {
                if (typeof $functionName !== 'function') {
                    return null;
                }
                var value = $functionName($argumentList);
                if (value == null) {
                    return null;
                }
                if (typeof value === 'string') {
                    return value;
                }
                return JSON.stringify(value);
            })();
        """.trimIndent()
        val result = RhinoScriptEngine.eval(js, scope, coroutineContext)
        return JsSourceEngine.normalizeJsResult(result, coroutineContext)
            ?.toString()
            ?.takeIf { it.isNotBlank() && it != "null" }
    }

    //endregion

    //region options()

    /** 解析并缓存 options() 结果，缓存键随脚本内容失效。 */
    fun loadOptions(source: HttpTTS): List<TtsScriptOption> {
        val script = source.script?.takeIf { it.isNotBlank() } ?: return emptyList()
        val cacheKey = "${source.id}:${MD5Utils.md5Encode16(script)}"
        synchronized(optionsCache) { optionsCache[cacheKey] }?.let { return it }
        val json = callEngineFunction(source, "options", coroutineContext = null)
            ?: return emptyList()
        val options = TtsScriptOption.parse(json)
        synchronized(optionsCache) { optionsCache[cacheKey] = options }
        return options
    }

    /** 用户保存的配置 + 默认值 + random_number 稳定生成，合成时传回脚本的 options 参数。 */
    fun effectiveOptions(source: HttpTTS, options: List<TtsScriptOption>): Map<String, String> {
        val saved = runCatching {
            GSON.fromJsonObject<Map<String, String>>(source.scriptOptionsJson).getOrNull().orEmpty()
        }.getOrNull().orEmpty()
        return options.associate { option ->
            val raw = saved[option.key]
            val value = when (option.normalizedType) {
                TtsScriptOptionType.RANDOM_NUMBER -> {
                    val digits = option.digits
                    val valid = raw != null && raw.length == digits &&
                        (!raw.startsWith("0") || option.allowLeadingZero) &&
                        raw.all(Char::isDigit)
                    if (valid) raw else TtsRandomNumberStore.stableValue(
                        source.id.toString(), option.key, digits, option.allowLeadingZero,
                    )
                }
                else -> raw?.takeIf { it.isNotBlank() } ?: option.defaultValue
            }
            option.key to value
        }
    }

    //endregion

    //region voices()

    /** 调用脚本 voices()，解析成标准音色目录。 */
    fun fetchVoices(source: HttpTTS): List<HttpTtsVoice> {
        val options = effectiveOptions(source, loadOptions(source))
        val json = callEngineFunction(
            source,
            "voices",
            listOf(GSON.toJson(options), GSON.toJson(extensionContext(source))),
        ) ?: return emptyList()
        return parseVoices(json)
    }

    internal fun parseVoices(json: String?): List<HttpTtsVoice> {
        if (json.isNullOrBlank()) return emptyList()
        val root = runCatching { JsonParser.parseString(json) }.getOrNull() ?: return emptyList()
        val array = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject -> root.asJsonObject.get("voices")
                ?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return array.mapNotNull { element ->
            (element as? JsonObject)?.toVoice()
        }.distinctBy(HttpTtsVoice::id)
    }

    private fun JsonObject.toVoice(): HttpTtsVoice? {
        val id = stringOf("id")?.takeIf(String::isNotBlank) ?: return null
        return HttpTtsVoice(
            id = id,
            name = stringOf("name")?.takeIf(String::isNotBlank) ?: id,
            language = stringOf("language").orEmpty(),
            gender = stringOf("gender").orEmpty(),
            style = stringOf("style").orEmpty(),
            tags = get("tags")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.filter { it.isJsonPrimitive }?.map { it.asString }.orEmpty(),
            extraJson = get("extra")?.takeIf { it.isJsonObject }?.toString().orEmpty(),
        )
    }

    private fun JsonObject.stringOf(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    //endregion

    //region synthesize()

    suspend fun getSynthesisStream(
        source: HttpTTS,
        text: String,
        voice: HttpTtsVoice? = null,
        styleId: String? = null,
        emotion: String = "",
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
    ): InputStream {
        return getSynthesisResponse(
            source, text, voice, styleId, emotion, coroutineContext, aggregate = false,
        ).requireAudioStream(source).body.byteStream()
    }

    /** 试听用：整段聚合后再返回，便于写临时文件。 */
    suspend fun getSynthesisBytes(
        source: HttpTTS,
        text: String,
        voice: HttpTtsVoice? = null,
        styleId: String? = null,
        emotion: String = "",
        coroutineContext: CoroutineContext = EmptyCoroutineContext,
    ): ByteArray {
        return getSynthesisResponse(
            source, text, voice, styleId, emotion, coroutineContext, aggregate = true,
        ).use { it.body.bytes() }
    }

    private suspend fun getSynthesisResponse(
        source: HttpTTS,
        text: String,
        voice: HttpTtsVoice?,
        styleId: String?,
        emotion: String,
        coroutineContext: CoroutineContext,
        aggregate: Boolean,
    ): Response {
        val options = effectiveOptions(source, loadOptions(source))
        val request = prepareSynthesisRequest(source, text, voice, styleId, emotion, options)
        val response = executeWithRetry(
            request = request,
            source = source,
            text = text,
            voiceId = voice?.id,
            coroutineContext = coroutineContext,
        )
        if (!aggregate) return response
        return response.use { original ->
            val contentType = original.body.contentType()
            val bytes = original.body.bytes()
            original.newBuilder()
                .body(bytes.toResponseBody(contentType))
                .build()
        }
    }

    private fun prepareSynthesisRequest(
        source: HttpTTS,
        text: String,
        voice: HttpTtsVoice?,
        styleId: String?,
        emotion: String,
        options: Map<String, String>,
    ): TtsScriptRequest {
        val params = mapOf(
            "speed" to 50,
            "volume" to 50,
            "pitch" to 50,
        )
        val voiceArg = voice.toScriptVoiceArg(resolveSelectedStyle(voice, styleId, emotion))
        val synthesis = callEngineFunction(
            source,
            "synthesize",
            listOf(
                GSON.toJson(text),
                GSON.toJson(voiceArg),
                GSON.toJson(params),
                GSON.toJson(options),
                GSON.toJson(extensionContext(source)),
            ),
        ) ?: throw NoStackTraceException("脚本未实现 synthesize(text, voice, params, options, ctx)")
        return parseSynthesisRequest(synthesis, source)
    }

    private fun extensionContext(source: HttpTTS): Map<String, Any?> {
        return mapOf(
            "engine" to mapOf(
                "id" to source.id.toString(),
                "name" to source.name,
            ),
            "app" to mapOf(
                "packageName" to appCtx.packageName,
                "versionName" to BuildConfig.VERSION_NAME,
            ),
            "synthesis" to null,
        )
    }

    private suspend fun executeWithRetry(
        request: TtsScriptRequest,
        source: HttpTTS,
        text: String,
        voiceId: String?,
        coroutineContext: CoroutineContext,
    ): Response {
        val attempts = request.retry.coerceIn(0, 3) + 1
        var lastError: Throwable? = null
        repeat(attempts) {
            try {
                return executeRequest(request, source, text, voiceId, coroutineContext)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastError = error
            }
        }
        throw lastError ?: NoStackTraceException("脚本合成请求失败")
    }

    private suspend fun executeRequest(
        request: TtsScriptRequest,
        source: HttpTTS,
        text: String,
        voiceId: String?,
        coroutineContext: CoroutineContext,
    ): Response {
        if (request.isWebSocket) {
            throw NoStackTraceException("当前版本暂不支持 WebSocket 传输的朗读引擎脚本")
        }
        val analyze = AnalyzeUrl(
            request.toAnalyzeUrlRule(),
            speakText = text,
            source = source,
            readTimeout = request.timeoutMillis ?: 300 * 1000L,
            coroutineContext = coroutineContext,
        )
        val response = analyze.getResponseAwait()
        if (request.isSse && response.isSuccessful) {
            return TtsSseEngineClient.transform(response, request)
        }
        return if (request.isJsonResponse) {
            response.toAudioResponse(request, source, voiceId, coroutineContext)
        } else {
            response
        }
    }

    private suspend fun Response.toAudioResponse(
        request: TtsScriptRequest,
        source: HttpTTS,
        voiceId: String?,
        coroutineContext: CoroutineContext,
    ): Response {
        val bodyText = body.string()
        val audioValue = extractAudioValue(bodyText, request.audioExtract)
            ?: throw NoStackTraceException(
                "脚本合成响应未找到音频字段: ${request.audioExtract.orEmpty()}"
            )
        return when (request.normalizedAudioEncoding) {
            "url" -> {
                val followRequest = request.copy(
                    url = audioValue,
                    method = "GET",
                    body = null,
                    responseType = "audio",
                    audioExtract = null,
                    audioEncoding = null,
                    retry = 0
                )
                executeRequest(followRequest, source, text = "", voiceId, coroutineContext)
            }
            "base64", "" -> {
                val audioBytes = audioValue.decodeBase64()?.toByteArray()
                    ?: throw NoStackTraceException("脚本合成响应音频字段不是有效 Base64")
                rebuildAudioBody(audioBytes, request.audioContentType)
            }
            else -> {
                rebuildAudioBody(audioValue.toByteArray(), request.audioContentType)
            }
        }
    }

    private fun Response.rebuildAudioBody(bytes: ByteArray, contentType: String?): Response {
        return newBuilder()
            .body(bytes.toResponseBody(contentType?.toMediaTypeOrNull()))
            .header("Content-Type", contentType ?: "application/octet-stream")
            .build()
    }

    internal fun extractAudioValue(body: String, path: String?): String? {
        val root = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return null
        path?.takeIf { it.isNotBlank() }?.let { explicitPath ->
            return root.elementAtPath(explicitPath)?.extractStringValue()
        }
        return when {
            root.isJsonPrimitive -> root.asString.takeIf { it.isNotBlank() }
            root.isJsonObject -> {
                val obj = root.asJsonObject
                listOf("audio", "audioContent", "audio_content", "data", "url", "result")
                    .firstNotNullOfOrNull { key ->
                        obj.get(key)?.extractStringValue()
                    }
            }
            else -> null
        }
    }

    private fun JsonElement.extractStringValue(): String? {
        return when {
            isJsonPrimitive -> asString.takeIf { it.isNotBlank() }
            isJsonObject -> asJsonObject.get("value")?.extractStringValue()
                ?: asJsonObject.get("audio")?.extractStringValue()
                ?: asJsonObject.get("url")?.extractStringValue()
            else -> null
        }
    }

    private fun Response.requireAudioStream(source: HttpTTS): Response {
        headers["Content-Type"]?.let { rawContentType ->
            val contentType = rawContentType.substringBefore(';').trim()
            val expected = defaultAudioType(source)
            if (contentType == "application/json" || contentType.startsWith("text/")) {
                throw NoStackTraceException(body.string())
            }
            if (expected.isNotBlank() && !contentType.matches(expected.toRegex())) {
                throw NoStackTraceException("TTS服务器返回错误：${body.string()}")
            }
        }
        return this
    }

    //endregion

    //region 请求描述解析

    internal fun parseSynthesisRequest(result: String, source: HttpTTS): TtsScriptRequest {
        val trimmed = result.trim()
        if (!trimmed.startsWith("{")) {
            return TtsScriptRequest(url = trimmed, audioContentType = defaultAudioType(source))
        }
        val obj = runCatching { JsonParser.parseString(trimmed).asJsonObject }.getOrNull()
            ?: throw NoStackTraceException("synthesize 返回值不是合法请求对象")
        val legacyContentType = obj.stringOf("contentType") ?: obj.stringOf("content_type")
        return TtsScriptRequest(
            url = obj.stringOf("url")
                ?: throw NoStackTraceException("synthesize(text, voice, params, options, ctx) 缺少 url"),
            method = obj.stringOf("method"),
            headers = obj.get("headers")?.takeIf { it.isJsonObject }?.asJsonObject
                ?.entrySet()?.associate { it.key to it.value.asString }.orEmpty(),
            body = obj.get("body")?.let {
                if (it.isJsonPrimitive) it.asString else GSON.toJson(it)
            },
            requestContentType = obj.stringOf("requestContentType")
                ?: obj.stringOf("request_content_type"),
            audioContentType = obj.stringOf("audioContentType")
                ?: obj.stringOf("audio_content_type")
                ?: obj.stringOf("audioType")
                ?: obj.stringOf("audio_type")
                ?: legacyContentType
                ?: defaultAudioType(source),
            responseType = obj.stringOf("responseType")
                ?: obj.stringOf("response_type")
                ?: if (obj.stringOf("audioExtract") != null || obj.stringOf("audio_extract") != null) {
                    "json"
                } else {
                    "audio"
                },
            audioExtract = obj.stringOf("audioExtract")
                ?: obj.stringOf("audio_extract")
                ?: obj.stringOf("audioPath")
                ?: obj.stringOf("audio_path"),
            audioEncoding = obj.stringOf("audioEncoding") ?: obj.stringOf("audio_encoding"),
            timeoutSeconds = obj.intOf("timeout") ?: obj.intOf("timeoutSeconds")
                ?: obj.intOf("timeout_seconds"),
            retry = obj.intOf("retry") ?: 0,
            transport = obj.stringOf("transport")
                ?: when {
                    obj.stringOf("url")?.startsWith("ws", ignoreCase = true) == true -> "websocket"
                    obj.get("stream")?.isJsonObject == true -> "sse"
                    else -> "http"
                },
            webSocketConfig = obj.get("websocket")?.takeIf { it.isJsonObject }
                ?.asJsonObject?.toWebSocketConfig(),
            sseConfig = obj.get("stream")?.takeIf { it.isJsonObject }
                ?.asJsonObject?.toSseConfig(),
        )
    }

    private fun JsonObject.intOf(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    private fun JsonObject.toWebSocketConfig(): TtsWebSocketConfig {
        return TtsWebSocketConfig(
            openMessages = get("openMessages").toMessageList(),
            textRules = get("textRules").toTextRules(),
            binaryAudio = get("binaryAudio")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
            finishOnClose = get("finishOnClose")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
            connectTimeoutSeconds = intOf("connectTimeout") ?: 15,
            firstAudioTimeoutSeconds = intOf("firstAudioTimeout") ?: 15,
            idleTimeoutSeconds = intOf("idleTimeout") ?: 15,
            finishGraceMillis = intOf("finishGrace") ?: 0,
            maxAudioBytes = intOf("maxAudioBytes") ?: TtsWebSocketConfig.DEFAULT_MAX_AUDIO_BYTES,
            pcm = get("pcm")?.toPcmConfig(),
        )
    }

    private fun JsonObject.toSseConfig(): TtsSseConfig {
        return TtsSseConfig(
            dataPrefix = stringOf("dataPrefix") ?: "data:",
            doneData = stringOf("doneData") ?: "[DONE]",
            finishOnEof = get("finishOnEof")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
            textRules = get("textRules").toTextRules(),
            maxAudioBytes = intOf("maxAudioBytes") ?: TtsWebSocketConfig.DEFAULT_MAX_AUDIO_BYTES,
            pcm = get("pcm")?.toPcmConfig(),
        )
    }

    private fun com.google.gson.JsonElement?.toPcmConfig(): TtsPcmConfig? {
        val obj = this?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val sampleRate = obj.intOf("sampleRate") ?: obj.intOf("sample_rate") ?: return null
        return TtsPcmConfig(
            sampleRate = sampleRate,
            channels = obj.intOf("channels") ?: 1,
            bitsPerSample = obj.intOf("bitsPerSample") ?: obj.intOf("bits_per_sample") ?: 16,
        )
    }

    private fun com.google.gson.JsonElement?.toTextRules(): List<TtsWebSocketTextRule> {
        return this?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val matchPath = obj.stringOf("matchPath") ?: return@mapNotNull null
                TtsWebSocketTextRule(
                    matchPath = matchPath,
                    equalsValue = obj.stringOf("equals"),
                    notEqualsValue = obj.stringOf("notEquals"),
                    sendMessages = obj.get("sendMessages").toMessageList(),
                    audioPath = obj.stringOf("audioPath"),
                    audioEncoding = obj.stringOf("audioEncoding"),
                    finish = obj.get("finish")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                    errorPath = obj.stringOf("errorPath"),
                )
            }
            .orEmpty()
    }

    private fun com.google.gson.JsonElement?.toMessageList(): List<String> {
        return this?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { element ->
                when {
                    element.isJsonPrimitive -> element.asString.takeIf { it.isNotBlank() }
                    element.isJsonObject || element.isJsonArray -> GSON.toJson(element)
                    else -> null
                }
            }
            .orEmpty()
    }

    internal data class TtsScriptRequest(
        val url: String,
        val method: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val requestContentType: String? = null,
        val audioContentType: String? = null,
        val responseType: String? = "audio",
        val audioExtract: String? = null,
        val audioEncoding: String? = null,
        val timeoutSeconds: Int? = null,
        val retry: Int = 0,
        val transport: String = "http",
        val webSocketConfig: TtsWebSocketConfig? = null,
        val sseConfig: TtsSseConfig? = null
    ) {
        val isJsonResponse: Boolean
            get() = responseType.orEmpty().equals("json", ignoreCase = true)

        val normalizedAudioEncoding: String
            get() = audioEncoding.orEmpty().trim().lowercase()

        val isWebSocket: Boolean
            get() = transport.equals("websocket", ignoreCase = true) ||
                    url.startsWith("ws://", ignoreCase = true) ||
                    url.startsWith("wss://", ignoreCase = true)

        val isSse: Boolean
            get() = transport.equals("sse", ignoreCase = true) ||
                    transport.equals("event-stream", ignoreCase = true)

        val timeoutMillis: Long?
            get() = timeoutSeconds?.takeIf { it > 0 }?.coerceAtMost(300)?.times(1000L)

        /** 转成 AnalyzeUrl 规则：`url,{method,headers,body}`。 */
        fun toAnalyzeUrlRule(): String {
            val option = JsonObject()
            method?.takeIf { it.isNotBlank() }?.let { option.addProperty("method", it) }
            val requestHeaders = headers.toMutableMap()
            requestContentType?.takeIf { it.isNotBlank() }?.let { contentType ->
                val hasContentType = requestHeaders.keys.any {
                    it.equals("Content-Type", ignoreCase = true)
                }
                if (!hasContentType) {
                    requestHeaders["Content-Type"] = contentType
                }
            }
            if (requestHeaders.isNotEmpty()) {
                option.add("headers", JsonParser.parseString(GSON.toJson(requestHeaders)))
            }
            body?.let { option.addProperty("body", it) }
            if (option.size() == 0) {
                return url
            }
            return "$url,${GSON.toJson(option)}"
        }
    }

    //endregion

    //region 音色风格

    internal data class TtsScriptVoiceStyle(
        val id: String?,
        val name: String?,
        val value: String?,
        val tag: String?,
    ) {
        val displayName: String get() = name ?: value ?: id.orEmpty()
        val scriptValue: String get() = value ?: id.orEmpty()
    }

    /**
     * 解析选中风格：显式 styleId > cue 情绪（匹配 extra.styles 的 id/name）> 音色默认 style。
     * 匹配不到时不编造 style，脚本按无风格处理。
     */
    internal fun resolveSelectedStyle(
        voice: HttpTtsVoice?,
        styleId: String?,
        emotion: String,
    ): TtsScriptVoiceStyle? {
        val styles = parseVoiceStyles(voice?.extraJson)
        val wanted = styleId?.takeIf { it.isNotBlank() }
            ?: emotion.takeIf { it.isNotBlank() }
            ?: voice?.style?.takeIf { it.isNotBlank() }
        if (wanted.isNullOrBlank()) return null
        val matched = styles.firstOrNull {
            it.id.equals(wanted, ignoreCase = true) || it.name == wanted || it.value == wanted
        }
        if (matched != null) return matched
        // 音色默认 style 或显式 styleId 不在目录里：原样传给脚本自行处理
        return TtsScriptVoiceStyle(id = wanted, name = wanted, value = wanted, tag = null)
            .takeIf { styleId != null || emotion.isBlank() }
    }

    private fun parseVoiceStyles(extraJson: String?): List<TtsScriptVoiceStyle> {
        if (extraJson.isNullOrBlank()) return emptyList()
        val extra = runCatching { JsonParser.parseString(extraJson) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        val array = extra.get("styles")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = obj.stringOf("id") ?: return@mapNotNull null
            TtsScriptVoiceStyle(
                id = id,
                name = obj.stringOf("name") ?: id,
                value = obj.stringOf("value") ?: id,
                tag = obj.stringOf("tag"),
            )
        }
    }

    private fun HttpTtsVoice?.toScriptVoiceArg(
        selectedStyle: TtsScriptVoiceStyle?,
    ): Map<String, Any?> {
        val extra = runCatching { JsonParser.parseString(this?.extraJson.orEmpty()) }
            .getOrNull()?.takeIf { it.isJsonObject }
        return mapOf(
            "id" to this?.id.orEmpty(),
            "name" to this?.name.orEmpty(),
            "language" to this?.language?.takeIf { it.isNotBlank() },
            "gender" to this?.gender?.takeIf { it.isNotBlank() },
            "style" to (selectedStyle?.displayName ?: this?.style?.takeIf { it.isNotBlank() }),
            "style_id" to selectedStyle?.id,
            "style_value" to selectedStyle?.scriptValue,
            "style_tag" to selectedStyle?.tag,
            "selected_style" to selectedStyle,
            "tags" to this?.tags.orEmpty(),
            "sample_text" to null,
            "extra" to extra,
        )
    }

    //endregion
}
