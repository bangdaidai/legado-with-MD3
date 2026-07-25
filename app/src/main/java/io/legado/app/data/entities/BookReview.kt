package io.legado.app.data.entities

import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

/**
 * 书评表 —— 对齐 readdai 的 BookReview（无 rating 字段，仅正文）。
 *
 * id 使用时间戳主键（与 readdai 同构）。bookUrl 关联书籍。
 */
@Entity(tableName = "bookReviews")
@Parcelize
data class BookReview(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "bookUrl")
    val bookUrl: String,

    @ColumnInfo(name = "bookName")
    val bookName: String,

    @ColumnInfo(name = "bookAuthor")
    val bookAuthor: String,

    @ColumnInfo(name = "reviewContent")
    val reviewContent: String,

    @ColumnInfo(name = "createTime")
    val createTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updateTime")
    val updateTime: Long = System.currentTimeMillis(),
) : Parcelable
