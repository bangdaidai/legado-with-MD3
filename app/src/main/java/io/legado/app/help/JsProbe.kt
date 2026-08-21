package io.legado.app.help

import com.script.rhino.RhinoProbe
import io.legado.app.constant.AppLog
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper

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
    private val lastToast = ThreadLocal.withInitial { arrayOfNulls<String>(1) }
    private val toastCount = ThreadLocal.withInitial { IntArray(1) }
    private val emittedToastCount = ThreadLocal.withInitial { IntArray(1) }

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
        // Java 对象在 JS 里是包装过的，直接 toString 只会打出 NativeJavaObject@xxx，要先脱壳
        value is Wrapper -> renderUnwrapped(value.unwrap())
        else -> renderUnwrapped(value)
    }

    private fun renderUnwrapped(value: Any?): String = when {
        value == null -> "null"
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

    /**
     * 书源按「异常文本长度」循环弹同一条提示，弹的次数就等于被吞掉的异常消息长度，
     * 所以这里累计次数，用来反推那条异常到底是什么。
     */
    fun onToast(msg: String?) {
        msg ?: return
        val holder = lastToast.get() ?: return
        val counter = toastCount.get() ?: return
        if (holder[0] == msg) {
            counter[0]++
        } else {
            holder[0] = msg
            counter[0] = 1
            emittedToastCount.get()?.set(0, 0)
        }
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
        val toastHolder = lastToast.get()
        val counter = toastCount.get()
        val emitted = emittedToastCount.get()
        val count = counter?.get(0) ?: 0
        // 上次已经把这轮提示打出去了，之后没有新提示，就别再重复挂在后面
        val toast = if (count > 0 && count != emitted?.get(0)) toastHolder?.get(0) else null
        if (toast == null && sb.isEmpty() && list.isNullOrEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastFlushAt < FLUSH_COOLDOWN) {
            // 冷却期内只是不输出，轨迹继续攒着，避免丢掉关键的那几步
            return
        }
        lastFlushAt = now
        if (toast != null) emitted?.set(0, count)
        val message = buildString {
            append("JsProbe[").append(where).append("] ").append(sb)
            if (toast != null) {
                append("toast×").append(count).append('=').append(brief(toast)).append(" ▸ ")
            }
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
