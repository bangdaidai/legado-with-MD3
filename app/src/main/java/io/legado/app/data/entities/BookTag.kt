package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 标签目录：单个标签（绑定颜色与所属分组）。
 * name 唯一约束防止同名标签出现（用户视为全新应用，无旧数据兼容问题）。
 */
@Entity(tableName = "bookTags", indices = [Index(value = ["name"], unique = true)])
data class BookTag(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "",
    val color: Long = 0,
    val groupId: Long = 0,
    /** 是否展示在书架标签筛选中 */
    val showOnBookshelf: Boolean = false,
    val createTime: Long = System.currentTimeMillis(),
    val updateTime: Long = System.currentTimeMillis(),
) {
    override fun hashCode(): Int = id.hashCode()
    override fun equals(other: Any?): Boolean {
        if (other is BookTag) return id == other.id
        return false
    }
}
