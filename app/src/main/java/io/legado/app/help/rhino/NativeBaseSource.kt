package io.legado.app.help.rhino

import com.script.rhino.JavaObjectWrapFactory
import io.legado.app.constant.AppLog
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable

class NativeBaseSource(scope: Scriptable?, javaObject: Any, staticType: Class<*>?) :
    NativeJavaObject(scope, javaObject, staticType) {

    override fun has(name: String, start: Scriptable): Boolean {
        if (name != "setVariable" && name.length > 3 && name.startsWith("set")) {
            val name = name.substring(3).replaceFirstChar { it.lowercase() }
            if (super.has(name, start)) {
                return false
            }
        }
        return super.has(name, start)
    }

    override fun get(name: String, start: Scriptable): Any? {
        if (name != "setVariable" && name.length > 3 && name.startsWith("set")) {
            val name = name.substring(3).replaceFirstChar { it.lowercase() }
            if (super.has(name, start)) {
                return NOT_FOUND
            }
        }
        val v = super.get(name, start)
        // === [QDProbe] 诊断日志: 定位起点助手书源验证失败具体读了哪些字段/返回了什么 ===
        // 用完后删除或注释掉这段。只打印首字符/长度以避免刷屏；跳过明显的方法调用。
        if (QDProbe.enabled) {
            runCatching {
                val isMethod = v is org.mozilla.javascript.NativeJavaMethod
                if (!isMethod) {
                    val src = javaObject
                    val srcTag = src?.javaClass?.simpleName ?: "?"
                    val vStr = when (v) {
                        null -> "null"
                        NOT_FOUND -> "NOT_FOUND"
                        is CharSequence -> "\"${v.toString().take(80).replace("\n", "\\n")}\"(len=${v.length})"
                        else -> "${v.javaClass.simpleName}=${v.toString().take(80).replace("\n", "\\n")}"
                    }
                    AppLog.put("[QDProbe] $srcTag.$name -> $vStr")
                }
            }
        }
        return v
    }

    override fun put(
        name: String,
        start: Scriptable,
        value: Any?
    ) {
        if (name == "variable") {
            super.put(name, start, value)
        }
    }

    companion object {
        val factory = JavaObjectWrapFactory { scope, javaObject, staticType ->
            NativeBaseSource(scope, javaObject, staticType)
        }
    }

}

/**
 * 临时诊断开关，用完请删除。
 * 在调试器或 adb shell 设置：setprop debug.qd_probe 1
 * 也可以直接把 enabled 改成 true 重新编译。
 */
object QDProbe {
    val enabled: Boolean = true  // ← 用完改 false 或删文件
}
