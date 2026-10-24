package io.legado.app.model.jsSource

import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * 脚本内的阻塞桥（java.ajax 等内部 runBlocking）不能把调用方正在阻塞的线程池
 * 当作事件循环复用，否则全池饱和时互相等待而死锁。
 * 去掉拦截器保留 Job，取消仍能传播进阻塞中的脚本。
 */
internal fun CoroutineContext.withoutScriptDispatcher(): CoroutineContext =
    minusKey(ContinuationInterceptor)
