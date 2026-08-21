package io.legado.app.help

import io.legado.app.constant.AppLog

/**
 * 临时探针：起点助手等书源把关键异常自己 try/catch 吞掉了，app 侧看不到失败点。
 *
 * 这里按线程收集 JS→Java 的调用轨迹，在「书源开始弹提示」或「加解密真的抛异常」时
 * 汇总成一条日志输出（带 3 秒冷却，避免书源循环弹 toast 时刷屏）。
 *
 * 问题定位后请整文件删除，并移除各调用点的 JsProbe.step / stepError / onToast。
 */
object JsProbe {

    private const val MAX_TRACE = 4000
    private const val FLUSH_COOLDOWN = 3000L

    private val trace = ThreadLocal.withInitial { StringBuilder() }

    @Volatile
    private var lastFlushAt = 0L

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
        step("toast", msg)
        if (msg.contains("暂不开放") || msg.contains("未开放")) flush("toast")
    }

    private fun flush(where: String) {
        val sb = trace.get() ?: return
        if (sb.isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastFlushAt < FLUSH_COOLDOWN) {
            sb.setLength(0)
            return
        }
        lastFlushAt = now
        AppLog.put("JsProbe[$where] $sb")
        sb.setLength(0)
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
