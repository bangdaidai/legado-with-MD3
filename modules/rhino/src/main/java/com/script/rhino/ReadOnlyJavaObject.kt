package com.script.rhino

import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable

class ReadOnlyJavaObject(scope: Scriptable?, javaObject: Any, staticType: Class<*>?) :
    NativeJavaObject(scope, javaObject, staticType) {

    override fun has(name: String, start: Scriptable): Boolean {
        if (name.length > 3 && name.startsWith("set")) {
            val name = name.substring(3).replaceFirstChar { it.lowercase() }
            if (super.has(name, start)) {
                return false
            }
        }
        return super.has(name, start)
    }

    override fun get(name: String, start: Scriptable): Any? {
        if (name.length > 3 && name.startsWith("set")) {
            val name2 = name.substring(3).replaceFirstChar { it.lowercase() }
            if (super.has(name2, start)) {
                RhinoProbe.onGet(javaObject.javaClass.simpleName, name, NOT_FOUND)
                return NOT_FOUND
            }
        }
        val value = super.get(name, start)
        RhinoProbe.onGet(javaObject.javaClass.simpleName, name, value)
        return value
    }

    override fun put(
        name: String?,
        start: Scriptable?,
        value: Any?
    ) {
        // 只读包装会静默丢弃写入，这里记一笔，避免书源赋值失败也看不出来
        RhinoProbe.onGet("${javaObject.javaClass.simpleName}(写入被丢弃)", name ?: "?", value)
    }

    companion object {
        val factory = JavaObjectWrapFactory { scope, javaObject, staticType ->
            ReadOnlyJavaObject(scope, javaObject, staticType)
        }
    }

}
