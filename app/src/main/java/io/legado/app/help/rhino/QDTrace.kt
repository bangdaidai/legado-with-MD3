package io.legado.app.help.rhino

import com.script.rhino.JavaObjectWrapFactory
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable

/**
 * 临时诊断：记录 JS 在 java/cache/cookie/source 上解析过的成员名，
 * 崩到 toast("书源验证失败") 时由 JsExtensions.toast 一次性 dump 成一条日志。
 * 用完删除本文件并回退 App.kt / JsExtensions.kt 的相关改动。
 */
object QDTrace {

    const val enabled = true

    private val buffer = ThreadLocal.withInitial { ArrayDeque<String>() }

    fun rec(entry: String) {
        if (!enabled) return
        val q = buffer.get()
        if (q.size >= 80) q.removeFirst()
        q.addLast(entry)
    }

    /** 返回本线程记录并清空 */
    fun dump(): String {
        val q = buffer.get()
        val s = q.joinToString(" > ")
        q.clear()
        return s
    }
}

/**
 * 只负责记录成员名，其余行为完全委托给父类，不改变语义。
 */
class TracingJavaObject(scope: Scriptable?, javaObject: Any, staticType: Class<*>?) :
    NativeJavaObject(scope, javaObject, staticType) {

    private val tag = javaObject.javaClass.simpleName

    override fun get(name: String, start: Scriptable): Any? {
        val v = super.get(name, start)
        if (v == NOT_FOUND) {
            QDTrace.rec("$tag.$name=<undefined>")
        } else {
            QDTrace.rec("$tag.$name")
        }
        return v
    }

    companion object {
        val factory = JavaObjectWrapFactory { scope, javaObject, staticType ->
            TracingJavaObject(scope, javaObject, staticType)
        }
    }
}
