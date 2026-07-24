package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.legado.app.constant.ReadingStatus
import kotlinx.parcelize.Parcelize

/**
 * 我的阅读实体类
 * 用于保存书籍的阅读详情信息，即使书籍从书架删除也会保留
 */
@Parcelize
@Entity(tableName = "readingMemories")
data class ReadingMemory(
    @PrimaryKey
    @ColumnInfo(name = "id")
    var id: String = "",

    @ColumnInfo(name = "bookUrl")
    var bookUrl: String = "",

    @ColumnInfo(name = "bookName")
    var bookName: String = "",

    @ColumnInfo(name = "bookAuthor")
    var bookAuthor: String = "",

    @ColumnInfo(name = "wordCount")
    var wordCount: String? = null,

    @ColumnInfo(name = "kind")
    var kind: String? = null,

    @ColumnInfo(name = "coverUrl")
    var coverUrl: String? = null,

    @ColumnInfo(name = "intro")
    var intro: String? = null,

    @ColumnInfo(name = "rating")
    var rating: Float = 0f,

    @ColumnInfo(name = "totalChapterNum")
    var totalChapterNum: Int = 0,

    @ColumnInfo(name = "durChapterIndex")
    var durChapterIndex: Int = 0,

    @ColumnInfo(name = "durChapterTitle")
    var durChapterTitle: String? = null,

    @ColumnInfo(name = "durChapterPos")
    var durChapterPos: Int = 0,

    @ColumnInfo(name = "progress")
    var progress: Float = 0f,

    @ColumnInfo(name = "readTime")
    var readTime: Long = 0L,

    @ColumnInfo(name = "annotationCount")
    var annotationCount: Int = 0,

    @ColumnInfo(name = "readingStatus")
    var readingStatus: Int = ReadingStatus.PENDING.value,

    @ColumnInfo(name = "userModifiedReadingStatus", defaultValue = "0")
    var userModifiedReadingStatus: Boolean = false,

    @ColumnInfo(name = "createTime")
    var createTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updateTime")
    var updateTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "userModifiedRating", defaultValue = "0")
    var userModifiedRating: Boolean = false,

    @ColumnInfo(name = "userModifiedIntro", defaultValue = "0")
    var userModifiedIntro: Boolean = false,

    @ColumnInfo(name = "userModifiedCover", defaultValue = "0")
    var userModifiedCover: Boolean = false,

    @ColumnInfo(name = "userModifiedWordCount", defaultValue = "0")
    var userModifiedWordCount: Boolean = false,

    @ColumnInfo(name = "userModifiedKind", defaultValue = "0")
    var userModifiedKind: Boolean = false,

    @ColumnInfo(name = "finishReadTime", defaultValue = "0")
    var finishReadTime: Long = 0L,

    @ColumnInfo(name = "firstReadTime", defaultValue = "0")
    var firstReadTime: Long = 0L,

    @ColumnInfo(name = "lastReadTime", defaultValue = "0")
    var lastReadTime: Long = 0L,

    @ColumnInfo(name = "readIteration", defaultValue = "0")
    var readIteration: Int = 0,

    @ColumnInfo(name = "type", defaultValue = "0")
    var type: Int = 0
) : Parcelable {

    /**
     * 获取阅读状态枚举
     */
    fun getStatus(): ReadingStatus = ReadingStatus.fromValue(readingStatus)

    /**
     * 获取阅读状态标签，作为书籍的第一个标签显示
     */
    fun getReadingStatusTag(): String {
        return when (getStatus()) {
            ReadingStatus.PENDING -> "待读"
            ReadingStatus.READING -> "在读"
            ReadingStatus.FINISHED -> "已读完"
            ReadingStatus.ABANDONED -> "弃文"
        }
    }

    /**
     * 获取阅读状态的显示文本
     */
    fun getReadingStatusDisplayText(): String = getStatus().displayName

    /**
     * 获取阅读状态的数值
     */
    fun getReadingStatusValue(): Int = readingStatus

    /**
     * 进度整数（0-100）
     */
    fun getProgressInt(): Int = progress.toInt().coerceIn(0, 100)

    /**
     * 阅读时长文本，如 "12小时30分"
     */
    fun getReadTimeText(): String {
        val totalMin = (readTime / 60000).toInt()
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) {
            "${h}小时${if (m > 0) "${m}分" else ""}"
        } else {
            "${m}分"
        }
    }
}
