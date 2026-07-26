package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 标签目录：单个标签（绑定颜色与所属分组）。
 */
@Entity(tableName = "bookTags")
data class BookTag(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "",
    val color: Long = 0,
    val groupId: Long = 0,
    val createTime: Long = System.currentTimeMillis(),
    val updateTime: Long = System.currentTimeMillis(),
) {
    override fun hashCode(): Int = id.hashCode()
    override fun equals(other: Any?): Boolean {
        if (other is BookTag) return id == other.id
        return false
    }
}
