package io.legado.app.service

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.IntentAction
import io.legado.app.constant.NotificationId
import io.legado.app.utils.IntentType
import io.legado.app.utils.openFileUri
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx
import splitties.systemservices.downloadManager
import splitties.systemservices.notificationManager

/**
 * 下载文件
 */
class DownloadService : BaseService() {
    private val groupKey = "${appCtx.packageName}.download"
    private val downloads = hashMapOf<Long, DownloadInfo>()

    // 已完成的下载 id -> 文件名：条目从 downloads 移出后，点通知仍能凭它打开文件
    private val completeDownloads = hashMapOf<Long, String>()
    private var upStateJob: Job? = null
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            queryState()
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(downloadReceiver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            IntentAction.start -> startDownload(
                intent.getStringExtra("url"),
                intent.getStringExtra("fileName")
            )

            IntentAction.play -> {
                val id = intent.getLongExtra("downloadId", 0)
                // 文件名随通知 extra 走：服务可能已因全部下载终态而停止重启，内存表不可靠
                val fileName = intent.getStringExtra("fileName")
                    ?: completeDownloads[id]
                    ?: downloads[id]?.fileName
                if (fileName != null) {
                    openDownload(id, fileName)
                } else {
                    toastOnUi("未完成,下载的文件夹Download")
                }
            }

            IntentAction.stop -> {
                val downloadId = intent.getLongExtra("downloadId", 0)
                removeDownload(downloadId)
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    /**
     * 开始下载
     */
    @Synchronized
    private fun startDownload(url: String?, fileName: String?) {
        if (url == null || fileName == null) {
            if (downloads.isEmpty()) {
                stopSelf()
            }
            return
        }
        if (downloads.values.any { it.url == url }) {
            toastOnUi("已在下载列表")
            return
        }
        kotlin.runCatching {
            // 指定下载地址
            val request = DownloadManager.Request(Uri.parse(url))
            // 设置通知
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            // 设置下载文件保存的路径和文件名
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            // 添加一个下载任务
            val downloadId = downloadManager.enqueue(request)
            downloads[downloadId] =
                DownloadInfo(url, fileName, NotificationId.Download + downloads.size)
            queryState()
            if (upStateJob == null) {
                checkDownloadState()
            }
        }.onFailure {
            it.printStackTrace()
            val msg = when (it) {
                is SecurityException -> "下载出错,没有存储权限"
                else -> "下载出错,${it.localizedMessage}"
            }
            toastOnUi(msg)
            AppLog.put(msg, it)
        }
    }

    /**
     * 取消下载
     */
    @Synchronized
    private fun removeDownload(downloadId: Long) {
        if (completeDownloads.containsKey(downloadId)) {
            completeDownloads.remove(downloadId)
        } else {
            downloadManager.remove(downloadId)
        }
        downloads.remove(downloadId)
        notificationManager.cancel(downloadId.toInt())
    }

    /**
     * 下载进入终态（成功/失败）：移出下载表。
     * 服务只为还在进行的下载保留前台，避免长时间空转撞上 dataSync 前台服务
     * 的 6 小时上限（Android 15+ 超时未停会抛 ForegroundServiceDidNotStopInTimeException）。
     */
    @Synchronized
    private fun finishDownload(downloadId: Long, success: Boolean) {
        val info = downloads.remove(downloadId) ?: return
        if (success) {
            completeDownloads[downloadId] = info.fileName
            openDownload(downloadId, info.fileName)
        }
    }

    private fun checkDownloadState() {
        upStateJob?.cancel()
        upStateJob = lifecycleScope.launch {
            while (isActive) {
                queryState()
                delay(1000)
            }
        }
    }

    /**
     * 查询下载进度
     */
    @Synchronized
    private fun queryState() {
        if (downloads.isEmpty()) {
            stopSelf()
            return
        }
        val ids = downloads.keys
        val query = DownloadManager.Query()
        query.setFilterById(*ids.toLongArray())
        // 终态先收集、循环外统一收尾，避免遍历时改动 downloads
        val finished = hashMapOf<Long, Boolean>()
        downloadManager.query(query).use { cursor ->
            if (!cursor.moveToFirst()) {
                // DownloadManager 里已查不到任何任务（记录被系统清理）：全部按失败收尾
                ids.toList().forEach { finishDownload(it, success = false) }
                stopSelf()
                return
            }
            val idIndex = cursor.getColumnIndex(DownloadManager.COLUMN_ID)
            val progressIndex =
                cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val fileSizeIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            do {
                val id = cursor.getLong(idIndex)
                val progress = cursor.getInt(progressIndex)
                val max = cursor.getInt(fileSizeIndex)
                val status = when (cursor.getInt(statusIndex)) {
                    DownloadManager.STATUS_PAUSED -> getString(R.string.pause)
                    DownloadManager.STATUS_PENDING -> getString(R.string.wait_download)
                    DownloadManager.STATUS_RUNNING -> getString(R.string.downloading)
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        finished[id] = true
                        getString(R.string.download_success)
                    }

                    DownloadManager.STATUS_FAILED -> {
                        finished[id] = false
                        getString(R.string.download_error)
                    }

                    else -> getString(R.string.unknown_state)
                }
                downloads[id]?.let { downloadInfo ->
                    upDownloadNotification(
                        id,
                        downloadInfo.notificationId,
                        "${downloadInfo.fileName} $status",
                        max,
                        progress,
                        downloadInfo.startTime,
                        downloadInfo.fileName,
                    )
                }
            } while (cursor.moveToNext())
        }
        finished.forEach { (id, success) -> finishDownload(id, success) }
        // 全部下载已终态：停掉前台服务，DownloadManager 自己会把剩余下载做完
        if (downloads.isEmpty()) {
            stopSelf()
        }
    }

    /**
     * 打开下载文件
     */
    private fun openDownload(downloadId: Long, fileName: String?) {
        kotlin.runCatching {
            downloadManager.getUriForDownloadedFile(downloadId)?.let { uri ->
                val type = IntentType.from(fileName)
                openFileUri(uri, type)
            }
        }.onFailure {
            AppLog.put("打开下载文件${fileName}出错", it)
        }
    }

    override fun startForegroundNotification() {
        val notification = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setGroup(groupKey)
            .setGroupSummary(true)
            .setOngoing(true)
            .build()
        startForeground(NotificationId.DownloadService, notification)
    }

    /**
     * 更新通知
     */
    private fun upDownloadNotification(
        downloadId: Long,
        notificationId: Int,
        content: String,
        max: Int,
        progress: Int,
        startTime: Long,
        fileName: String,
    ) {
        val notificationBuilder = NotificationCompat.Builder(this, AppConst.channelIdDownload)
            .setSmallIcon(R.drawable.ic_download)
            .setSubText(getString(R.string.action_download))
            .setContentTitle(content)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                servicePendingIntent<DownloadService>(IntentAction.play, downloadId.toInt()) {
                    putExtra("downloadId", downloadId)
                    putExtra("fileName", fileName)
                }
            )
            .setDeleteIntent(
                servicePendingIntent<DownloadService>(IntentAction.stop, downloadId.toInt()) {
                    putExtra("downloadId", downloadId)
                }
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setGroup(groupKey)
            .setWhen(startTime)
        if (progress < max) {
            notificationBuilder.setProgress(max, progress, false)
        }
        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    private data class DownloadInfo(
        val url: String,
        val fileName: String,
        val notificationId: Int,
        val startTime: Long = System.currentTimeMillis()
    )

}
