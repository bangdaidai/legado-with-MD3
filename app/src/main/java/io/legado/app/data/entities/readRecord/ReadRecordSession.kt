package io.legado.app.data.entities.readRecord

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "readRecordSession")
data class ReadRecordSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val deviceId: String = "",
    val bookName: String = "",
    @ColumnInfo(defaultValue = "")
    val bookAuthor: String = "",

    // 一次阅读的开始/结束
    val startTime: Long = 0,
    val endTime: Long = 0,

    // 本次阅读的字数
    val words: Long = 0,

    // 书籍类型位标记 (BookType)
    @ColumnInfo(defaultValue = "8")
    val bookType: Int = io.legado.app.constant.BookType.text,

    // 章节标题（视频为集数名，文本为章节名；冗余存储避免删书后丢失）
    @ColumnInfo(defaultValue = "")
    val chapterTitle: String = ""
)
