package io.legado.app.model.jsSource

import com.google.gson.JsonObject
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.BookSourceType
import io.legado.app.data.entities.BookSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import kotlin.coroutines.CoroutineContext

/** 从单文件脚本提取稳定的 [BookSource] 配置并校验函数契约。 */
object JsSourceConfig {

    private const val CONFIG_PROPERTY = "config"
    private const val LEGACY_CONFIG_PROPERTY = "source"

    val requiredFunctions = listOf("search", "getChapters", "getContent")
    private val fileSourceRequiredFunctions = listOf("search", "getBookInfo")
    private val strippedKeys = listOf(
        "mainJs",
        "ruleSearch",
        "ruleExplore",
        "ruleBookInfo",
        "ruleToc",
        "ruleContent",
        "ruleReview",
    )

    /**
     * 在无运行时绑定的安全作用域里执行脚本，提取顶层 config（兼容旧版 source），
     * 校验必需函数后返回携带完整脚本文本（mainJs）的书源。
     */
    fun extract(
        text: String,
        coroutineContext: CoroutineContext? = null,
    ): BookSource {
        try {
            return extractInternal(text, coroutineContext)
        } catch (error: CancellationException) {
            throw error
        }
    }

    private fun extractInternal(
        text: String,
        coroutineContext: CoroutineContext?,
    ): BookSource {
        val scope = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
        JsSourceCrypto.install(scope, coroutineContext)
        try {
            RhinoScriptEngine.eval(text, scope, coroutineContext)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw NoStackTraceException("JS源脚本执行失败: ${error.message}")
        }
        val (configName, config) = findConfig(scope)
        val json = JsSourceEngine.normalizeJsResult(config, coroutineContext)
            ?: throw NoStackTraceException("$configName 配置对象无法解析")
        val jsonObject = runCatching { GSON.fromJson(json, JsonObject::class.java) }.getOrNull()
            ?: throw NoStackTraceException("$configName 配置对象不是合法对象")
        strippedKeys.forEach(jsonObject::remove)
        normalizeExploreUrl(jsonObject)
        normalizeLoginUi(jsonObject)
        val source = runCatching { GSON.fromJson(jsonObject, BookSource::class.java) }.getOrNull()
            ?: throw NoStackTraceException("$configName 配置对象字段类型不符")
        if (source.bookSourceUrl.isBlank()) {
            throw NoStackTraceException("JS源 $configName.bookSourceUrl 不能为空")
        }
        if (source.bookSourceName.isBlank()) {
            throw NoStackTraceException("JS源 $configName.bookSourceName 不能为空")
        }
        val required = if (source.bookSourceType == BookSourceType.file) {
            fileSourceRequiredFunctions
        } else {
            requiredFunctions
        }
        required.forEach { name ->
            if (ScriptableObject.getProperty(scope, name) !is Function) {
                throw NoStackTraceException("JS源缺少必备函数 $name")
            }
        }
        if (!source.exploreUrl.isNullOrBlank() &&
            ScriptableObject.getProperty(scope, "explore") !is Function
        ) {
            throw NoStackTraceException("JS源声明了 exploreUrl，缺少配对的 explore 函数")
        }
        val loginUiFunction = ScriptableObject.getProperty(scope, "loginUi")
        if (loginUiFunction is Function) {
            // 本版本未移植动态登录协议（loginUi(state)/loginAction），显式拒绝以免静默失效
            throw NoStackTraceException(
                "JS源使用了动态登录（loginUi 函数），当前版本暂不支持"
            )
        }
        if (!source.loginUi.isNullOrBlank() &&
            ScriptableObject.getProperty(scope, "login") !is Function
        ) {
            throw NoStackTraceException("JS源声明了 loginUi，缺少配对的 login 函数")
        }
        source.mainJs = text
        return source
    }

    private fun findConfig(scope: Scriptable): Pair<String, Any> {
        val config = ScriptableObject.getProperty(scope, CONFIG_PROPERTY)
        val legacyConfig = ScriptableObject.getProperty(scope, LEGACY_CONFIG_PROPERTY)
        val hasConfig = config != null && config !== Scriptable.NOT_FOUND
        val hasLegacyConfig = legacyConfig != null && legacyConfig !== Scriptable.NOT_FOUND
        if (hasConfig && (!hasLegacyConfig || isCompleteConfig(config))) {
            return CONFIG_PROPERTY to requireNotNull(config)
        }
        if (hasLegacyConfig) {
            return LEGACY_CONFIG_PROPERTY to requireNotNull(legacyConfig)
        }
        throw NoStackTraceException("JS源缺少顶层 config 配置对象（兼容旧版 source）")
    }

    private fun isCompleteConfig(value: Any?): Boolean {
        val json = JsSourceEngine.normalizeJsResult(value) ?: return false
        val jsonObject = runCatching { GSON.fromJson(json, JsonObject::class.java) }.getOrNull()
            ?: return false
        return runCatching {
            jsonObject.get("bookSourceUrl")?.asString?.isNotBlank() == true &&
                jsonObject.get("bookSourceName")?.asString?.isNotBlank() == true
        }.getOrDefault(false)
    }

    private fun normalizeExploreUrl(jsonObject: JsonObject) {
        val element = jsonObject.get("exploreUrl") ?: return
        if (!element.isJsonArray) return
        val array = element.asJsonArray
        if (array.size() == 0) {
            jsonObject.remove("exploreUrl")
            return
        }
        array.forEachIndexed { index, item ->
            val title = runCatching { item.asJsonObject.get("title")?.asString }.getOrNull()
            if (title.isNullOrBlank()) {
                throw NoStackTraceException("exploreUrl 第 ${index + 1} 项缺少 title")
            }
        }
        jsonObject.addProperty("exploreUrl", GSON.toJson(array))
    }

    private fun normalizeLoginUi(jsonObject: JsonObject) {
        val element = jsonObject.get("loginUi") ?: return
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            if (element.asString.filterNot(Char::isWhitespace) == "[]") {
                jsonObject.remove("loginUi")
            }
            return
        }
        if (!element.isJsonArray) return
        val array = element.asJsonArray
        if (array.size() == 0) {
            jsonObject.remove("loginUi")
            return
        }
        array.forEachIndexed { index, item ->
            val name = runCatching { item.asJsonObject.get("name")?.asString }.getOrNull()
            if (name.isNullOrBlank()) {
                throw NoStackTraceException("loginUi 第 ${index + 1} 项缺少 name")
            }
        }
        jsonObject.addProperty("loginUi", GSON.toJson(array))
    }
}
