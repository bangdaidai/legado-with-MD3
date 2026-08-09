package com.script.rhino

/**
 * Thread-local probe: records every NativeJavaObject.get() call during JS execution.
 * Call dump() to get one combined string, then the buffer clears.
 * Set enabled=false when done debugging.
 */
object RhinoProbe {
    @JvmField var enabled = true
    @JvmField var sink: ((String) -> Unit)? = null // set by app module

    private val buffer = ThreadLocal.withInitial { StringBuilder(8192) }
    private val count = ThreadLocal.withInitial { intArrayOf(0) }

    fun rec(objectType: String, name: String, value: Any?, notFound: Boolean) {
        if (!enabled) return
        val sb = buffer.get()
        val n = count.get()
        n[0]++
        if (sb.isNotEmpty()) sb.append(" → ")
        sb.append(n[0]).append('.')
        sb.append(objectType).append('.').append(name)
        if (notFound) {
            sb.append("=⛔NOT_FOUND")
        } else when (value) {
            null -> sb.append("=null")
            is CharSequence -> {
                val s = value.toString().replace("\n", "\\n")
                if (s.length > 120) sb.append("=\"").append(s.take(120)).append("…\"(").append(s.length).append(')')
                else sb.append("=\"").append(s).append('"')
            }
            is Number -> sb.append('=').append(value)
            else -> sb.append("=[").append(value.javaClass.simpleName).append(']')
        }
    }

    fun dump(): String {
        val sb = buffer.get()
        val result = sb.toString()
        sb.clear()
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
