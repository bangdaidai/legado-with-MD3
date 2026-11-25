package io.legado.app.model.jsSource

import com.script.CompiledScript
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import splitties.init.appCtx
import java.security.SecureRandom
import kotlin.coroutines.CoroutineContext

/**
 * JS 书源内置 CryptoJS。NG 系脚本普遍直接使用 CryptoJS.MD5 等全局对象。
 * 资产读取放在 lazy + runCatching 内：JVM 单测没有 Android 环境，
 * 读取失败静默跳过注入，脚本本身仍可执行（只是没有 CryptoJS 可用）。
 */
internal object JsSourceCrypto {

    private const val CRYPTO_JS_ASSET = "scripts/cryptojs.min.js"
    private const val SECURE_RANDOM_BINDING = "__legadoSecureRandomInt"

    private val lock = Any()
    private val secureRandom by lazy { SecureRandom() }

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
        val secureRandomFunction = object : BaseFunction() {
            override fun call(
                cx: Context,
                callScope: Scriptable,
                thisObj: Scriptable,
                args: Array<Any?>?,
            ): Any = secureRandom.nextInt().toDouble()
        }.apply {
            parentScope = scope
            prototype = ScriptableObject.getFunctionPrototype(scope)
        }
        ScriptableObject.putProperty(scope, SECURE_RANDOM_BINDING, secureRandomFunction)
        try {
            // Rhino 没有 CryptoJS 依赖的原生 crypto，随机字改用 JCA 提供。
            RhinoScriptEngine.eval(SECURE_RANDOM_PATCH, scope, coroutineContext)
        } finally {
            scope.delete(SECURE_RANDOM_BINDING)
        }
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

    private const val SECURE_RANDOM_PATCH = """
        CryptoJS.lib.WordArray.random = (function(nextInt) {
            return function(nBytes) {
                var words = [];
                for (var i = 0; i < nBytes; i += 4) {
                    words.push(nextInt());
                }
                return CryptoJS.lib.WordArray.create(words, nBytes);
            };
        })(__legadoSecureRandomInt);
    """
}
