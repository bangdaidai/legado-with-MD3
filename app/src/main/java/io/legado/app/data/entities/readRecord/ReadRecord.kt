package io.legado.app.data.entities.readRecord

import androidx.room.ColumnInfo
import androidx.room.Entity

@Entity(tableName = "readRecord", primaryKeys = ["deviceId", "bookName", "bookAuthor"])
data class ReadRecord(
    var deviceId: String = "",
    var bookName: String = "",
    @ColumnInfo(defaultValue = "")
    var bookAuthor: String = "",
    @ColumnInfo(defaultValue = "0")
    var readTime: Long = 0L,
    @ColumnInfo(defaultValue = "0")
    var lastRead: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "8")
    var bookType: Int = io.legado.app.constant.BookType.text,
    // 封面URL（影视记录等不在书架的记录用于显示封面）
    @ColumnInfo(defaultValue = "")
    var coverUrl: String = ""
)