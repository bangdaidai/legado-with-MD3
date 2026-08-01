package io.legado.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.legado.app.data.appDb
import io.legado.app.data.entities.readRecord.ReadRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 接收外部 TV/影视 App 发来的观看记录广播。
 * Action: io.legado.app.action.ADD_WATCH_RECORD
 * Extras: bookName(String), readTime(Long, ms)
 */
class WatchRecordReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "io.legado.app.action.ADD_WATCH_RECORD"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val bookName = intent.getStringExtra("bookName") ?: return
        val readTime = intent.getLongExtra("readTime", 0L)
        if (bookName.isBlank() || readTime <= 0) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val record = appDb.readRecordDao.getByDeviceAndBook(android.os.Build.DEVICE, bookName)
                if (record != null) {
                    appDb.readRecordDao.update(record.copy(
                        readTime = record.readTime + readTime,
                        lastRead = System.currentTimeMillis()
                    ))
                } else {
                    appDb.readRecordDao.insert(ReadRecord(
                        deviceId = android.os.Build.DEVICE,
                        bookName = bookName,
                        readTime = readTime,
                        lastRead = System.currentTimeMillis()
                    ))
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
