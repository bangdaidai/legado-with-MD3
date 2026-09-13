package io.legado.app.help.source

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.IntentData
import io.legado.app.ui.association.VerificationCodeActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.isMainThread
import io.legado.app.utils.startActivity
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * 源验证
 */
object SourceVerificationHelp {

    private val waitTime = 1.minutes.inWholeNanoseconds

    /**
     * 搜索期间禁止弹窗标志。
     * 为 true 时 [getVerificationResult] 会立即抛出异常，跳过浏览器/验证码弹窗。
     */
    @Volatile
    var suppressPopup = false

    /** 正在等待用户完成人机验证的书源：sourceKey -> 等待开始时间（纳秒） */
    private val pendingVerification = ConcurrentHashMap<String, Long>()

    /** sourceKey -> 最近一次人机验证成功的时间（纳秒），无条目表示从未成功 */
    private val lastVerifiedOkAt = ConcurrentHashMap<String, Long>()

    fun isWaitingVerification(sourceKey: String): Boolean =
        pendingVerification.containsKey(sourceKey)

    /** 该书源最近是否成功完成过一次人机验证（用于超时重试判定） */
    fun wasVerifiedRecently(
        sourceKey: String,
        within: Duration = 10.minutes,
    ): Boolean {
        val at = lastVerifiedOkAt[sourceKey] ?: return false
        return System.nanoTime() - at < within.inWholeNanoseconds
    }

    private fun getVerificationResultKey(source: BaseSource) =
        getVerificationResultKey(source.getKey())

    private fun getVerificationResultKey(sourceKey: String) = "${sourceKey}_verificationResult"

    /**
     * 获取书源验证结果
     * 图片验证码 防爬 滑动验证码 点击字符 等等
     */
    @Synchronized
    fun getVerificationResult(
        source: BaseSource?,
        url: String,
        title: String,
        useBrowser: Boolean,
        refetchAfterSuccess: Boolean = true,
        html: String? = null
    ): Pair<String, String> {
        source
            ?: throw NoStackTraceException("getVerificationResult parameter source cannot be null")
        require(url.length < 64 * 1024) { "getVerificationResult parameter url too long" }
        check(!isMainThread) { "getVerificationResult must be called on a background thread" }

        if (suppressPopup) {
            throw NoStackTraceException("搜索期间禁止弹窗，跳过验证")
        }

        val sourceKey = source.getKey()
        pendingVerification[sourceKey] = System.nanoTime()
        var verified = false
        try {
            doVerification(source, url, title, useBrowser, refetchAfterSuccess, html).also {
                verified = true
            }
        } finally {
            pendingVerification.remove(sourceKey)
            // 只有验证真正成功才记录，失败（用户关闭验证页）不触发搜索侧重试
            if (verified) {
                lastVerifiedOkAt[sourceKey] = System.nanoTime()
            }
        }
    }

    private fun doVerification(
        source: BaseSource,
        url: String,
        title: String,
        useBrowser: Boolean,
        refetchAfterSuccess: Boolean,
        html: String?,
    ): Pair<String, String> {
        clearResult(source.getKey())

        if (!useBrowser) {
            appCtx.startActivity<VerificationCodeActivity> {
                putExtra("imageUrl", url)
                putExtra("sourceOrigin", source.getKey())
                putExtra("sourceName", source.getTag())
                putExtra("sourceType", source.getSourceType())
                IntentData.put(getVerificationResultKey(source), Thread.currentThread())
            }
        } else {
            startBrowser(source, url, title, true, refetchAfterSuccess, html)
        }

        var waitUserInput = false
        while (getResult(source.getKey()) == null) {
            if (!waitUserInput && html == null) {
                AppLog.putDebug("等待返回验证结果...")
                waitUserInput = true
            }
            LockSupport.parkNanos(this, waitTime)
        }
        val result = getResult(source.getKey()) ?: throw NoStackTraceException("验证结果为空")
        clearResult(source.getKey())
        if (result.second.isEmpty()) throw NoStackTraceException("验证结果为空")
        return result
    }

    /** 挂起等待该书源当前的人机验证流程结束（无论成功或失败） */
    suspend fun awaitVerificationIdle(sourceKey: String) {
        while (isWaitingVerification(sourceKey)) {
            kotlinx.coroutines.delay(500)
        }
    }

    /**
     * 启动内置浏览器
     * @param saveResult 保存网页源代码到数据库
     */
    fun startBrowser(
        source: BaseSource?,
        url: String,
        title: String,
        saveResult: Boolean? = false,
        refetchAfterSuccess: Boolean? = true,
        html: String? = null
    ) {
        source ?: throw NoStackTraceException("startBrowser parameter source cannot be null")
        require(url.length < 64 * 1024) { "startBrowser parameter url too long" }
        IntentData.put(getVerificationResultKey(source), Thread.currentThread())
        appCtx.startActivity(
            MainActivity.createWebViewIntent(
                appCtx, title, url, source.getKey(), source.getTag(), source.getSourceType(),
                saveResult == true, refetchAfterSuccess != false, html,
            )
        )
    }


    fun checkResult(sourceKey: String) {
        getResult(sourceKey) ?: setResult(sourceKey, "")
        val thread = IntentData.get<Thread>(getVerificationResultKey(sourceKey))
        LockSupport.unpark(thread)
    }

    fun setResult(sourceKey: String, result: String, url: String = "") {
        CacheManager.putMemory(getVerificationResultKey(sourceKey), (url to result))
    }

    fun getResult(sourceKey: String): Pair<String, String>? {
        val pair = CacheManager.getFromMemory(getVerificationResultKey(sourceKey)) as? Pair<*, *>
            ?: return null
        if (pair.first is String && pair.second is String) {
            return pair.first as String to pair.second as String
        }
        return null
    }

    fun clearResult(sourceKey: String) {
        CacheManager.delete(getVerificationResultKey(sourceKey))
    }
}
