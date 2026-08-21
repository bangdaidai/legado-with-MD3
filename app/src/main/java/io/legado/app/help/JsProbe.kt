package io.legado.app.help

import com.script.rhino.RhinoProbe
import io.legado.app.constant.AppLog
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined

/**
 * 临时探针：起点助手等书源把关键异常自己 try/catch 吞掉了，app 侧看不到失败点。
 *
 * 两层信息：
 * 1. JS→Java 的调用轨迹（step / stepError / onToast）
 * 2. JS 访问 Java 成员的顺序与取值（RhinoProbe 回调），用来发现混淆书源
 *    按字符下标拼出来的名字在本 fork 里落空
 *
 * 触发输出的时机：书源弹提示、加解密真的抛异常、验证网页回传。
 *
 * 问题定位后请整文件删除，并移除各调用点的 JsProbe.* 与 RhinoProbe.onGet。
 */
object JsProbe {

    private const val MAX_TRACE = 4000
    private const val MAX_MEMBERS = 200
    private const val FLUSH_COOLDOWN = 3000L

    private val trace = ThreadLocal.withInitial { StringBuilder() }
    private val members = ThreadLocal.withInitial { ArrayDeque<String>() }

    @Volatile
    private var lastFlushAt = 0L

    /** 在 initRhino 里接上包装层回调 */
    fun install() {
        RhinoProbe.sink = { owner, name, value -> onMember(owner, name, value) }
    }

    private fun onMember(owner: String, name: String, value: Any?) {
        val list = members.get() ?: return
        if (list.size >= MAX_MEMBERS) list.removeFirst()
        list.addLast("$owner.$name = ${renderMember(value)}")
    }

    private fun renderMember(value: Any?): String = when {
        value == null -> "null"
        value === Scriptable.NOT_FOUND -> "<不存在>"
        value is Undefined -> "undefined"
        value is Function -> "<method>"
        value is CharSequence -> "\"${brief(value)}\""
        else -> brief(value)
    }

    fun step(tag: String, value: Any? = null) {
        val sb = trace.get() ?: return
        if (sb.length > MAX_TRACE) {
            sb.setLength(0)
            sb.append("(轨迹超长已重置) ")
        }
        sb.append(tag)
        if (value != null) sb.append('=').append(brief(value))
        sb.append(" ▸ ")
    }

    /** 记录异常并立即汇总输出，用于书源会吞掉的失败点 */
    fun stepError(tag: String, e: Throwable) {
        step("✗$tag", e)
        flush(tag)
    }

    /** 书源自己弹提示时说明它已经进了兜底分支，此刻把轨迹倒出来 */
    fun onToast(msg: String?) {
        msg ?: return
        val sb = trace.get() ?: return
        // 书源按异常文本长度循环弹同一条提示，只记一次，别把前面的关键轨迹挤掉
        val entry = "toast=${brief(msg)} ▸ "
        if (!sb.endsWith(entry)) sb.append(entry)
        flush("toast")
    }

    /** 强制输出当前轨迹，忽略冷却，用于验证网页回传这种必看节点 */
    fun dump(where: String) {
        lastFlushAt = 0L
        flush(where)
    }

    private fun flush(where: String) {
        val sb = trace.get() ?: return
        val list = members.get()
        if (sb.isEmpty() && list.isNullOrEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastFlushAt < FLUSH_COOLDOWN) {
            // 冷却期内只是不输出，轨迹继续攒着，避免丢掉关键的那几步
            return
        }
        lastFlushAt = now
        val message = buildString {
            append("JsProbe[").append(where).append("] ").append(sb)
            if (!list.isNullOrEmpty()) {
                append("\n成员访问(").append(list.size).append("条):")
                list.forEachIndexed { index, entry ->
                    append('\n').append(index + 1).append(". ").append(entry)
                }
            }
        }
        AppLog.put(message)
        sb.setLength(0)
        list?.clear()
    }

    private fun brief(value: Any): String {
        val text = when (value) {
            is ByteArray -> "bytes(${value.size})"
            is Throwable -> "${value.javaClass.simpleName}: ${value.message}"
            else -> value.toString()
        }
        val oneLine = text.replace('\n', '↵')
        return if (oneLine.length > 120) oneLine.take(120) + "…" else oneLine
    }
}
