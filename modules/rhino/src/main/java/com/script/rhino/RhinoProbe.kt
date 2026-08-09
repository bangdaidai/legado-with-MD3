package com.script.rhino

import org.mozilla.javascript.NativeJavaMethod
import org.mozilla.javascript.Wrapper

/**
 * Thread-local probe: records every Java member access from JS.
 * Dumped once per top-level eval. Set enabled=false when done debugging.
 */
object RhinoProbe {
    @JvmField var enabled = true
    @JvmField var sink: ((String) -> Unit)? = null
    /** app 模块注入：把任意 Java 对象序列化成 JSON，用于看清 Rule 对象内容 */
    @JvmField var jsonFormatter: ((Any?) -> String?)? = null

    private val buffer = ThreadLocal.withInitial { StringBuilder(16384) }
    private val count = ThreadLocal.withInitial { intArrayOf(0) }

    fun rec(objectType: String, name: String, value: Any?, notFound: Boolean) {
        if (!enabled) return
        val sb = buffer.get()
        if (sb.length > 400_000) return
        val n = count.get()
        n[0]++
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(n[0]).append(". ").append(objectType).append('.').append(name).append(" = ")
        if (notFound) {
            sb.append("<NOT_FOUND>")
            return
        }
        sb.append(describe(value))
    }

    /** 直接追加一行原始文本（不带编号），供 Debugger 使用 */
    fun raw(line: String) {
        if (!enabled) return
        val sb = buffer.get()
        if (sb.length > 400_000) return
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(line)
    }

    /** 供 Debugger 复用的值格式化 */
    fun describePublic(value: Any?): String = describe(value)

    private fun describe(value: Any?): String = try {
        when (value) {
            null -> "null"
            is CharSequence -> quote(value.toString())
            is Number, is Boolean -> value.toString()
            is NativeJavaMethod -> "<method>"
            is Wrapper -> describeJava(value.unwrap())
            else -> describeJava(value)
        }
    } catch (e: Throwable) {
        "<err:${e.javaClass.simpleName}>"
    }

    private fun describeJava(raw: Any?): String {
        if (raw == null) return "null"
        if (raw is CharSequence) return quote(raw.toString())
        if (raw is Number || raw is Boolean) return raw.toString()
        val cls = raw.javaClass
        // 基础类型/JDK 类型直接 toString
        if (cls.name.startsWith("java.") || cls.name.startsWith("kotlin.")) {
            return "${cls.simpleName}:" + quote(raw.toString())
        }
        // 业务对象：交给 app 注入的 GSON 序列化，看清全部字段
        val json = jsonFormatter?.invoke(raw)
        return if (json != null) "${cls.simpleName}:$json" else "${cls.simpleName}:" + quote(raw.toString())
    }

    private fun quote(s: String): String {
        val one = s.replace("\n", "\\n").replace("\r", "")
        return if (one.length > 600) "\"" + one.take(600) + "…\"(len=" + one.length + ")"
        else "\"" + one + "\""
    }

    fun dump(): String {
        val sb = buffer.get()
        val result = sb.toString()
        sb.setLength(0)
        count.get()[0] = 0
        return result
    }

    fun dumpToSink(prefix: String) {
        val content = dump()
        if (content.isNotEmpty()) {
            sink?.invoke("$prefix\n$content")
        }
    }
}
