package io.legado.app.model.jsSource

import androidx.collection.LruCache
import cn.hutool.core.codec.Base64
import com.google.gson.JsonObject
import com.script.CompiledScript
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import com.script.rhino.enterRhinoContext
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.JsExtensions
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.help.source.getShareScope
import io.legado.app.quickjs.QuickJsSandboxBridge
import io.legado.app.quickjs.QuickJsSandboxExecution
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.InflaterInputStream
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * 纯 JavaScript 单文件书源执行器。
 *
 * 每次业务调用创建全新局部作用域并重新求值主脚本（编译结果按脚本文本缓存），
 * 共享部分只读地挂在作用域父级：jsLib 共享库或内置 CryptoJS。
 * java 宿主仅额外提供字符串输入输出的 QuickJS 隔离执行入口。
 * 声明式书源继续走原有 AnalyzeRule 链，两种书源只在 WebBook 入口分流。
 */
class JsSourceEngine(
    private val source: BookSource,
    coroutineContext: CoroutineContext? = null,
) : JsExtensions {

    private val coroutineContext =
        coroutineContext?.withoutScriptDispatcher() ?: EmptyCoroutineContext

    private val quickJsSandboxBridge by lazy { QuickJsSandboxBridge(appCtx) }
    private val quickJsExecution = ThreadLocal<Lazy<QuickJsSandboxExecution>>()

    override fun getSource(): BaseSource = source

    override fun getTag(): String = source.getTag()

    /** 仅单文件 JS 运行时可达，QuickJS 本体不暴露给 Rhino。 */
    fun getQuickJsSandbox(): QuickJsSandboxBridge {
        val execution = quickJsExecution.get()?.value ?: return quickJsSandboxBridge
        execution.prepare()
        return execution.bridge
    }

    /** 发送 NGJS 的二进制 POST；限制传输协议和正文大小，避免明文传输及无界内存分配。 */
    fun postBase64Body(url: String, bodyBase64: String, headersJson: String?): String {
        val httpUrl = url.toHttpUrl()
        require(httpUrl.isHttps) { "二进制请求只允许 HTTPS" }
        val body = Base64.decode(bodyBase64)
        require(body.isNotEmpty()) { "二进制请求正文不能为空" }
        require(body.size <= MAX_BINARY_REQUEST_BYTES) { "二进制请求正文过大" }
        val headers = headersJson
            ?.let { GSON.fromJsonObject<Map<String, String>>(it).getOrNull() }
            ?: emptyMap()
        val contentType = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.toMediaTypeOrNull()
        val response = runBlocking(coroutineContext) {
            okHttpClient.newCallResponse {
                url(httpUrl)
                addHeaders(headers)
                post(body.toRequestBody(contentType))
            }
        }
        response.use {
            return JsonObject().apply {
                addProperty("code", it.code)
                addProperty("body", it.body.text())
            }.toString()
        }
    }

    private fun <T> withQuickJsExecution(block: () -> T): T {
        val previous = quickJsExecution.get()
        val execution = lazy { QuickJsSandboxExecution(appCtx) }
        quickJsExecution.set(execution)
        return try {
            block()
        } finally {
            if (previous == null) quickJsExecution.remove() else quickJsExecution.set(previous)
            if (execution.isInitialized()) execution.value.close()
        }
    }

    /** 把 UTF-8 文本压成可安全跨 Rhino 边界传递的 Base64（gzip/zlib 自动识别解包）。 */
    fun gzipUtf8ToBase64(value: String): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(value.toByteArray(Charsets.UTF_8)) }
        return android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP)
    }

    /** 解开 Base64 中的 gzip/zlib 数据；未压缩输入按 UTF-8 原样返回。 */
    fun decompressBase64ToUtf8(value: String): String {
        val input = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
        val stream = when {
            input.size >= 2 && input[0] == 0x1f.toByte() && input[1] == 0x8b.toByte() ->
                GZIPInputStream(ByteArrayInputStream(input))

            input.size >= 2 && input[0] == 0x78.toByte() ->
                InflaterInputStream(ByteArrayInputStream(input))

            else -> ByteArrayInputStream(input)
        }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun callFunction(name: String, args: List<Pair<String, Any?>>): String? =
        withQuickJsExecution {
            val scope = buildScope(args)
            if (ScriptableObject.getProperty(scope, name) !is Function) {
                throw NoStackTraceException("JS源缺少函数 $name")
            }
            val expression = "$name(${args.joinToString(", ") { it.first }})"
            normalizeJsResult(
                compile(expression).eval(scope, coroutineContext),
                coroutineContext,
            )
        }

    fun callFunctionIfExists(name: String, args: List<Pair<String, Any?>>): String? =
        withQuickJsExecution {
            val scope = buildScope(args)
            if (ScriptableObject.getProperty(scope, name) !is Function) {
                return@withQuickJsExecution null
            }
            val expression = "$name(${args.joinToString(", ") { it.first }})"
            normalizeJsResult(
                compile(expression).eval(scope, coroutineContext),
                coroutineContext,
            )
        }

    fun callJsonFunction(name: String, jsonArgs: List<String?>): String? =
        withQuickJsExecution {
            val scope = buildScope(emptyList())
            if (ScriptableObject.getProperty(scope, name) !is Function) {
                throw NoStackTraceException("JS源缺少函数 $name")
            }
            val expression = "$name(${jsonArgs.joinToString(", ") { json ->
                json?.let { "JSON.parse(${GSON.toJson(it)})" } ?: "null"
            }})"
            normalizeJsResult(
                compile(expression).eval(scope, coroutineContext),
                coroutineContext,
            )
        }

    private fun buildScope(args: List<Pair<String, Any?>>): Scriptable {
        val script = source.mainJs?.takeIf { it.isNotBlank() }
            ?: throw NoStackTraceException("mainJs 为空，不是 JS 书源")
        val bindings = buildScriptBindings { values ->
            values["java"] = this
            values["source"] = source
            values["sourceApi"] = source
            values["baseUrl"] = source.getKey()
            values["cookie"] = CookieStore
            values["cache"] = CacheManager
            args.forEach { (key, value) -> values[key] = value }
        }
        val sharedScope = source.getShareScope(coroutineContext)
        val scope = if (sharedScope == null) {
            bindings.apply { prototype = JsSourceCrypto.cryptoScope(coroutineContext) }
        } else {
            bindings.apply { prototype = sharedScope }
        }
        compile(script).eval(scope, coroutineContext)
        return scope
    }

    companion object {

        private val scriptCache = LruCache<String, CompiledScript>(64)
        private const val MAX_BINARY_REQUEST_BYTES = 512 * 1024

        private fun compile(script: String): CompiledScript {
            scriptCache.get(script)?.let { return it }
            return RhinoScriptEngine.compile(script).also { scriptCache.put(script, it) }
        }

        fun normalizeJsResult(
            result: Any?,
            coroutineContext: CoroutineContext? = null,
        ): String? {
            var value = result
            if (value is Wrapper) value = value.unwrap()
            return when {
                value == null || value is Undefined -> null
                value is String -> value
                value is CharSequence -> value.toString()
                value is Scriptable -> stringifyScriptable(value, coroutineContext)
                    ?: GSON.toJson(value)
                else -> GSON.toJson(value)
            }
        }

        private fun stringifyScriptable(
            value: Scriptable,
            coroutineContext: CoroutineContext?,
        ): String? {
            val topScope = value.parentScope?.let(ScriptableObject::getTopLevelScope)
                ?: return null
            val context = enterRhinoContext()
            val previousCoroutineContext = context.coroutineContext
            val previousAllowScriptRun = context.allowScriptRun
            if (coroutineContext != null && coroutineContext[Job] != null) {
                context.coroutineContext = coroutineContext
            }
            context.allowScriptRun = true
            context.recursiveCount++
            try {
                context.checkRecursive()
                val raw = try {
                    NativeJSON.stringify(context, topScope, value, null, null)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw NoStackTraceException(
                        "JS返回值 JSON.stringify 失败: ${error.message}"
                    )
                }
                return RhinoScriptEngine.unwrapReturnValue(raw) as? String
            } finally {
                context.recursiveCount--
                context.allowScriptRun = previousAllowScriptRun
                context.coroutineContext = previousCoroutineContext
                Context.exit()
            }
        }
    }
}
