package com.script.rhino

import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.debug.DebugFrame
import org.mozilla.javascript.debug.DebuggableScript
import org.mozilla.javascript.debug.Debugger

/**
 * 临时诊断：逐函数记录 JS 执行过程，退出时 dump 全部局部变量。
 * 用完删除本文件并 revert RhinoContext / RhinoScriptEngine 的相关改动。
 */
object QDDebugger : Debugger {

    override fun handleCompilationDone(cx: Context?, fnOrScript: DebuggableScript?, source: String?) {
        // 不记录编译事件，噪音太大
    }

    override fun getFrame(cx: Context?, fnOrScript: DebuggableScript?): DebugFrame {
        return QDDebugFrame(fnOrScript)
    }
}

private class QDDebugFrame(private val script: DebuggableScript?) : DebugFrame {

    private val name: String =
        script?.functionName?.takeIf { it.isNotBlank() } ?: "<script>"

    private var activation: Scriptable? = null

    override fun onEnter(cx: Context?, activation: Scriptable?, thisObj: Scriptable?, args: Array<out Any>?) {
        this.activation = activation
        if (!RhinoProbe.enabled) return
        val argStr = args?.joinToString(", ") { RhinoProbe.describePublic(it) } ?: ""
        RhinoProbe.raw("→ ENTER $name($argStr)")
    }

    override fun onLineChange(cx: Context?, lineNumber: Int) {
        // 行级别太吵，只在需要时打开
    }

    override fun onExceptionThrown(cx: Context?, ex: Throwable?) {
        if (!RhinoProbe.enabled) return
        RhinoProbe.raw("✗ THROW in $name: ${ex?.javaClass?.simpleName}: ${ex?.message?.take(200)}")
    }

    override fun onExit(cx: Context?, byThrow: Boolean, resultOrException: Any?) {
        if (!RhinoProbe.enabled) return
        val locals = dumpLocals()
        val ret = if (byThrow) "THROW ${RhinoProbe.describePublic(resultOrException)}"
        else "return ${RhinoProbe.describePublic(resultOrException)}"
        RhinoProbe.raw("← EXIT  $name -> $ret${if (locals.isEmpty()) "" else "\n    locals: $locals"}")
    }

    override fun onDebuggerStatement(cx: Context?) {
    }

    private fun dumpLocals(): String {
        val act = activation ?: return ""
        return try {
            val ids = act.ids ?: return ""
            ids.filterIsInstance<String>()
                .take(60)
                .joinToString(", ") { id ->
                    val v = try { act.get(id, act) } catch (_: Throwable) { "<err>" }
                    "$id=" + RhinoProbe.describePublic(if (v === Scriptable.NOT_FOUND) null else v)
                }
        } catch (_: Throwable) {
            ""
        }
    }
}
