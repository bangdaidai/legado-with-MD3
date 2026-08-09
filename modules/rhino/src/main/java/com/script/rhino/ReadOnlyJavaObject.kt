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
            val propName = name.substring(3).replaceFirstChar { it.lowercase() }
            if (super.has(propName, start)) {
                RhinoProbe.rec(javaObject?.javaClass?.simpleName ?: "?", name, null, true)
                return NOT_FOUND
            }
        }
        val v = super.get(name, start)
        RhinoProbe.rec(javaObject?.javaClass?.simpleName ?: "?", name, v, v == NOT_FOUND)
        return v
    }

    override fun put(
        name: String?,
        start: Scriptable?,
        value: Any?
    ) {
        // do nothing
    }

    companion object {
        val factory = JavaObjectWrapFactory { scope, javaObject, staticType ->
            ReadOnlyJavaObject(scope, javaObject, staticType)
        }
    }

}
