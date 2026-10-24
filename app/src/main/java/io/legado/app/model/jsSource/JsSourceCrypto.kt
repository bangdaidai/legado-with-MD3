package io.legado.app.model.jsSource

import com.script.CompiledScript
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import splitties.init.appCtx
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import kotlin.coroutines.CoroutineContext

/**
 * JS 书源内置 CryptoJS。NG 系脚本普遍直接使用 CryptoJS.MD5 等全局对象。
 * 资产读取放在 lazy + runCatching 内：JVM 单测没有 Android 环境，
 * 读取失败静默跳过注入，脚本本身仍可执行（只是没有 CryptoJS 可用）。
 */
internal object JsSourceCrypto {

    private const val CRYPTO_JS_ASSET = "scripts/cryptojs.min.js"

    private val lock = Any()

    @Volatile
    private var compiled: CompiledScript? = null

    @Volatile
    private var cryptoScopeRef: Scriptable? = null

    private fun compiledCryptoJs(): CompiledScript? {
        compiled?.let { return it }
        synchronized(lock) {
            compiled?.let { return it }
            val js = runCatching {
                appCtx.assets.open(CRYPTO_JS_ASSET).use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull() ?: return null
            return RhinoScriptEngine.compile(js).also { compiled = it }
        }
    }

    /** 在目标作用域安装 CryptoJS，已安装时跳过。 */
    fun install(scope: Scriptable, coroutineContext: CoroutineContext?) {
        val existing = ScriptableObject.getProperty(scope, "CryptoJS")
        if (existing != null && existing !== Scriptable.NOT_FOUND &&
            existing != Undefined.instance
        ) {
            return
        }
        val cryptoJs = compiledCryptoJs() ?: return
        cryptoJs.eval(scope, coroutineContext)
    }

    /**
     * 仅含 CryptoJS 的共享作用域，作主脚本作用域的父级。
     * 主脚本每次调用都在全新 bindings 作用域里执行，这里共享的只是只读的库对象。
     */
    fun cryptoScope(coroutineContext: CoroutineContext?): Scriptable {
        cryptoScopeRef?.let { return it }
        synchronized(lock) {
            cryptoScopeRef?.let { return it }
            val scope = RhinoScriptEngine.run {
                getRuntimeScope(ScriptBindings())
            }
            install(scope, coroutineContext)
            cryptoScopeRef = scope
            return scope
        }
    }
}
