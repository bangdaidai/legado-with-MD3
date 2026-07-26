package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 书籍与标签的多对多关联。
 */
@Entity(tableName = "bookTagRelations")
data class BookTagRelation(
    @PrimaryKey
    val id: String = "",
    val bookUrl: String = "",
    val tagId: Long = 0,
    val createTime: Long = System.currentTimeMillis(),
) {
    override fun hashCode(): Int = id.hashCode()
    override fun equals(other: Any?): Boolean {
        if (other is BookTagRelation) return id == other.id
        return false
    }
}
