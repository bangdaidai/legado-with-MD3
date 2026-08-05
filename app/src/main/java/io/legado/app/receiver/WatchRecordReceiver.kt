package io.legado.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 接收外部 TV/影视 App 发来的观看记录广播。
 * Action: io.legado.app.action.ADD_WATCH_RECORD
 * Extra: "json" — JSON string of WatchRecordData
 */
class WatchRecordReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "io.legado.app.action.ADD_WATCH_RECORD"
    }

    data class WatchRecordData(
        val bookName: String = "",
        val author: String = "",
        val bookUrl: String = "",
        val coverUrl: String = "",
        val duration: Long = 0L,
        val startTime: Long = 0L,
        val endTime: Long = 0L,
        val episodeTitle: String = ""
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val json = intent.getStringExtra("json") ?: return
        val record = runCatching { Gson().fromJson(json, WatchRecordData::class.java) }
            .getOrNull() ?: return
        if (record.bookName.isBlank()) return

        val deviceId = android.os.Build.DEVICE
        val duration = if (record.duration > 0) record.duration
            else if (record.endTime > record.startTime) record.endTime - record.startTime
            else return
        val endTime = if (record.endTime > 0) record.endTime else System.currentTimeMillis()
        val startTime = if (record.startTime > 0) record.startTime else endTime - duration

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Upsert ReadRecord (aggregate)
                val existing = appDb.readRecordDao.getReadRecord(
                    deviceId, record.bookName, record.author
                )
                if (existing != null) {
                    appDb.readRecordDao.update(existing.copy(
                        readTime = existing.readTime + duration,
                        lastRead = System.currentTimeMillis(),
                        bookType = BookType.video
                    ))
                } else {
                    appDb.readRecordDao.insert(ReadRecord(
                        deviceId = deviceId,
                        bookName = record.bookName,
                        bookAuthor = record.author,
                        readTime = duration,
                        lastRead = System.currentTimeMillis(),
                        bookType = BookType.video
                    ))
                }

                // 2. Upsert ReadRecordDetail (daily aggregate)
                val date = Instant.ofEpochMilli(startTime)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ISO_LOCAL_DATE)
                val existingDetail = appDb.readRecordDao.getDetail(
                    deviceId, record.bookName, record.author, date
                )
                if (existingDetail != null) {
                    appDb.readRecordDao.insertDetail(existingDetail.copy(
                        readTime = existingDetail.readTime + duration,
                        lastReadTime = System.currentTimeMillis(),
                        bookType = BookType.video
                    ))
                } else {
                    appDb.readRecordDao.insertDetail(ReadRecordDetail(
                        deviceId = deviceId,
                        bookName = record.bookName,
                        bookAuthor = record.author,
                        date = date,
                        readTime = duration,
                        firstReadTime = startTime,
                        lastReadTime = endTime,
                        bookType = BookType.video
                    ))
                }

                // 3. Insert ReadRecordSession
                appDb.readRecordDao.insertSession(ReadRecordSession(
                    deviceId = deviceId,
                    bookName = record.bookName,
                    bookAuthor = record.author,
                    startTime = startTime,
                    endTime = endTime,
                    words = 0,
                    bookType = BookType.video
                ))
            } finally {
                pendingResult.finish()
            }
        }
    }
}

