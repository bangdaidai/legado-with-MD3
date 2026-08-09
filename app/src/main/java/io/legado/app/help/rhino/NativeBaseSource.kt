package io.legado.app.help.rhino

import com.script.rhino.JavaObjectWrapFactory
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
            val propName = name.substring(3).replaceFirstChar { it.lowercase() }
            if (super.has(propName, start)) {
                com.script.rhino.RhinoProbe.rec("source", name, null, true)
                return NOT_FOUND
            }
        }
        val v = super.get(name, start)
        com.script.rhino.RhinoProbe.rec("source", name, v, v == NOT_FOUND)
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
