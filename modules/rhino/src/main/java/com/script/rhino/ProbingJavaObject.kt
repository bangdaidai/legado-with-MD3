package com.script.rhino

import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.Scriptable

/**
 * 临时探针用的包装：除已注册专用包装的类型外，其余 Java 对象都用这个包，
 * 只在 get 上多记一笔，其他行为与 NativeJavaObject 完全一致。
 *
 * 没有它的话 java.* / cache.* / cookie.* 这些访问全都看不到，
 * 轨迹里就缺了最关键的一段。
 *
 * 问题定位后请整文件删除，并把 RhinoWrapFactory 里的 probeWrap 改回 super。
 */
class ProbingJavaObject(scope: Scriptable?, javaObject: Any, staticType: Class<*>?) :
    NativeJavaObject(scope, javaObject, staticType) {

    override fun get(name: String, start: Scriptable): Any? {
        val value = super.get(name, start)
        RhinoProbe.onGet(javaObject.javaClass.simpleName, name, value)
        return value
    }
}
