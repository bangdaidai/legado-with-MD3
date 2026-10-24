package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toEpochDays
import kotlinx.datetime.toLocalDateTime

/**
 * 今日书讯 — 按天记录书架在线书确认到的目录增长。
 * 只在目录刷新真实提交后发现 totalChapterNum 增长时写入；绝不从
 * latestChapterTime 等历史时间戳推断，基线未知时章数保持 null。
 */
@Entity(
    tableName = "bookUpdates",
    primaryKeys = ["epochDay", "bookUrl", "origin"],
    indices = [Index(value = ["bookUrl", "origin", "epochDay"])],
)
data class BookUpdate(
    /** 发现日（本地时区 epoch day），主键之一：同一天同书只保留一条 */
    val epochDay: Long,
    val bookUrl: String,
    val origin: String,
    val latestChapterTitle: String?,
    /** 发现时刻，用于当日列表排序 */
    val foundAt: Long,
    /** 发现时的目录总数 */
    val totalChapterNum: Int,
    /** 当日首次发现时的基线章数；null 表示基线未知，不伪造章数 */
    val baselineChapterNum: Int? = null,
)

/** null 表示基线未知（含早期记录），展示为"已更新"而非 0 章 */
fun bookUpdateChapterCount(baselineChapterNum: Int?, totalChapterNum: Int): Int? {
    baselineChapterNum ?: return null
    return if (baselineChapterNum > 0 && totalChapterNum > baselineChapterNum) {
        totalChapterNum - baselineChapterNum
    } else {
        null
    }
}

/** 同日重复发现只允许 total 前进，且保留当日首个基线，避免章数越滚越大 */
fun mergeBookUpdateIfNewer(previous: BookUpdate?, incoming: BookUpdate): BookUpdate? {
    if (previous == null) return incoming
    if (previous.epochDay != incoming.epochDay || previous.bookUrl != incoming.bookUrl ||
        previous.origin != incoming.origin || incoming.totalChapterNum <= previous.totalChapterNum
    ) {
        return null
    }
    return incoming.copy(baselineChapterNum = previous.baselineChapterNum)
}

fun bookUpdateEpochDay(foundAt: Long, timeZone: TimeZone = TimeZone.currentSystemDefault()): Long =
    Instant.fromEpochMilliseconds(foundAt).toLocalDateTime(timeZone).date.toEpochDays()
