package com.script.rhino

import org.mozilla.javascript.NativeJavaClass
import org.mozilla.javascript.Scriptable

class ProtectedNativeJavaClass(
    scope: Scriptable,
    javaClass: Class<*>,
    private val protectedName: Set<String> = emptySet()
) : NativeJavaClass(scope, javaClass) {

    override fun has(
        name: String,
        start: Scriptable?
    ): Boolean {
        if (protectedName.contains(name)) {
            return false
        }
        return super.has(name, start)
    }

    override fun get(name: String, start: Scriptable?): Any? {
        if (protectedName.contains(name)) {
            RhinoProbe.onGet(probeOwner(), name, NOT_FOUND)
            return NOT_FOUND
        }
        val value = super.get(name, start)
        RhinoProbe.onGet(probeOwner(), name, value)
        return value
    }

    private fun probeOwner(): String {
        val target = javaObject
        return if (target is Class<*>) target.simpleName else target.toString()
    }

    override fun put(
        name: String,
        start: Scriptable?,
        value: Any?
    ) {
        if (protectedName.contains(name)) {
            return
        }
        super.put(name, start, value)
    }

    override fun unwrap(): Any? {
        return javaObject.toString()
    }

}
