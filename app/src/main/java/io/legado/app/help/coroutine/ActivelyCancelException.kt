package io.legado.app.help.coroutine

import kotlin.coroutines.cancellation.CancellationException

class ActivelyCancelException : CancellationException {

    constructor() : super()

    /**
     * 带原因的主动取消：原因沿协程树传给子任务（典型：在飞的 AI 请求），
     * 日志里能看到"为什么被取消"，而不是一个协程名 "y1 was cancelled"。
     */
    constructor(reason: String) : super(reason)

    override fun fillInStackTrace(): Throwable {
        stackTrace = emptyArray()
        return this
    }

}
