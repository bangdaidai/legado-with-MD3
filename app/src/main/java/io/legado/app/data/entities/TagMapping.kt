package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 标签映射：把分类自动生成的异名（oldTagName，如「玄幻小说」）映射到同一标准标签（newTagId，如「玄幻」）。
 * 在 TagManager.generateTagsFromKind 解析书籍 kind 时先查此表做异名归一。
 */
@Entity(tableName = "tagMappings")
data class TagMapping(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val oldTagName: String = "",
    val newTagId: Long = 0,
    val createTime: Long = System.currentTimeMillis(),
) {
    override fun hashCode(): Int = id.hashCode()
    override fun equals(other: Any?): Boolean {
        if (other is TagMapping) return id == other.id
        return false
    }
}
