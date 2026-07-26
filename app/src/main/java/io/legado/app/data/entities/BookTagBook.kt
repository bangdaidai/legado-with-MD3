package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 标签关联书籍的冗余快照，用于单标签页展示关联书籍（封面/书名/作者）。
 */
@Entity(tableName = "bookTagBooks")
data class BookTagBook(
    @PrimaryKey
    val id: String = "",
    val bookUrl: String = "",
    val tagName: String = "",
    val bookName: String = "",
    val author: String = "",
    val coverUrl: String = "",
    val createTime: Long = System.currentTimeMillis(),
) {
    override fun hashCode(): Int = id.hashCode()
    override fun equals(other: Any?): Boolean {
        if (other is BookTagBook) return id == other.id
        return false
    }
}
