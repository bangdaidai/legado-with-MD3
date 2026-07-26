package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 排除标签：命中后从自动生成的标签与选择列表中剔除。
 * isRegex=true 表示按正则匹配，false 表示按普通包含匹配。
 */
@Entity(tableName = "excludedTags")
data class ExcludedTag(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "",
    val isRegex: Boolean = false,
    val createTime: Long = System.currentTimeMillis(),
    val updateTime: Long = System.currentTimeMillis(),
) {
    override fun hashCode(): Int = id.hashCode()
    override fun equals(other: Any?): Boolean {
        if (other is ExcludedTag) return id == other.id
        return false
    }
}
