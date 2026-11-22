package io.legado.app.base

import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Notification
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.CallSuper
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.legado.app.constant.AppLog
import io.legado.app.R
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import io.legado.app.data.repository.SettingsRepository
import io.legado.app.help.LifecycleHelp
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.koin.java.KoinJavaComponent.get
import kotlin.coroutines.CoroutineContext

abstract class BaseService : LifecycleService() {

    /** 上一次 startForeground 被系统拒绝时置 true（配额耗尽/后台启动限制），服务据此自行退出。 */
    protected var foregroundStartRejected = false
        private set

    private val simpleName = this::class.simpleName.toString()
    @Volatile
    private var isForeground = false

    fun <T> execute(
        scope: CoroutineScope = lifecycleScope,
        context: CoroutineContext = Dispatchers.IO,
        start: CoroutineStart = CoroutineStart.DEFAULT,
        executeContext: CoroutineContext = Dispatchers.Main,
        semaphore: Semaphore? = null,
        block: suspend CoroutineScope.() -> T
    ) = Coroutine.async(scope, context, start, executeContext, semaphore, block)

    @CallSuper
    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        isForeground = true
        LifecycleHelp.onServiceCreate(this)
        val localPreferencesRepository: SettingsRepository = get(SettingsRepository::class.java)
        //DataStore 读写不能用 runBlocking 卡住 onCreate 所在的主线程
        lifecycleScope.launch {
            val checked: Boolean = localPreferencesRepository.getPreference(
                LocalPreferencesKeys.PERMISSION_CHECKED, false
            ).first()
            if (!checked) {
                localPreferencesRepository.updatePreference(
                    LocalPreferencesKeys.PERMISSION_CHECKED, true
                )
                checkPermission()
            }
        }
    }

    @CallSuper
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogUtils.d(simpleName) {
            "onStartCommand $intent ${intent?.toUri(0)}"
        }
        if (!isForeground) {
            startForegroundNotification()
            isForeground = true
        }
        return super.onStartCommand(intent, flags, startId)
    }

    @CallSuper
    override fun onTaskRemoved(rootIntent: Intent?) {
        LogUtils.d(simpleName, "onTaskRemoved")
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    @CallSuper

    /**
     * 安全地进入前台：系统拒绝时返回 false 而不是抛出。
     *
     * [foregroundServiceType] 为 0 时沿用清单声明的类型。
     */
    protected fun startForegroundSafely(
        id: Int,
        notification: Notification,
        foregroundServiceType: Int = 0
    ): Boolean {
        return try {
            if (foregroundServiceType != 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, id, notification, foregroundServiceType)
            } else {
                startForeground(id, notification)
            }
            isForeground = true
            true
        } catch (e: Exception) {
            onForegroundStartFailed(e)
            false
        }
    }

    /**
     * 发布前台通知：尚未在前台时进入前台，已在前台时只更新通知内容。
     *
     * 服务已在运行时不能再调用 [startForeground]：Android 15 起同一 UID 的 dataSync /
     * mediaProcessing 共享「任意 24 小时累计 6 小时」配额，配额耗尽后重复调用会抛
     * [ForegroundServiceStartNotAllowedException]。
     */
    protected fun postForegroundNotification(
        id: Int,
        notification: Notification,
        foregroundServiceType: Int = 0
    ): Boolean {
        if (isForeground) {
            notificationManager.notify(id, notification)
            return true
        }
        return startForegroundSafely(id, notification, foregroundServiceType)
    }

    private fun onForegroundStartFailed(e: Exception) {
        foregroundStartRejected = true
        val limitedBySystem = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            e is ForegroundServiceStartNotAllowedException
        AppLog.put(
            if (limitedBySystem) {
                "系统拒绝启动前台服务($simpleName)：该类型在 24 小时内累计运行已满上限" +
                    "或当前不允许后台启动，服务将退出"
            } else {
                "前台服务启动失败，服务将退出($simpleName)"
            },
            e,
            true
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        isForeground = false
        LifecycleHelp.onServiceDestroy(this)
    }

    @CallSuper
    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        LogUtils.d(simpleName, "onTimeout startId:$startId fgsType:$fgsType")
        // dataSync 等类型超时后系统只给几秒宽限：先摘掉前台状态再停服务，
        // 防止主线程忙碌导致 stopSelf 生效过晚，触发 ForegroundServiceDidNotStopInTimeException
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 开启前台服务并发送通知
     */
    open fun startForegroundNotification() {

    }

    /**
     * 检测通知权限和后台权限
     */
    private fun checkPermission() {
        PermissionsCompat.Builder()
            .addPermissions(Permissions.POST_NOTIFICATIONS)
            .rationale(R.string.notification_permission_rationale)
            .request()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PermissionsCompat.Builder()
                .addPermissions(Permissions.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .rationale(R.string.ignore_battery_permission_rationale)
                .request()
        }
    }
}
