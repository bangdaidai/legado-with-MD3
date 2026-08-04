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
        // === [QDProbe] 诊断: 起点助手 QDSign 指纹校验读取 source 字段。
        // 一次性把整个 source 序列化成 JSON 打到一条日志（喂给指纹算法的原料），用完删除。
        if (QDProbe.enabled) {
            runCatching {
                val src = javaObject
                if (src != null && QDProbe.dumped.add(System.identityHashCode(src))) {
                    val json = io.legado.app.utils.GSON.toJson(src)
                    AppLog.put("[QDProbe] ${src.javaClass.simpleName} JSON = $json")
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
    val dumped: MutableSet<Int> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Int, Boolean>())
}
