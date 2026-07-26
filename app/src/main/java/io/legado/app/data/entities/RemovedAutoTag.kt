package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 已移除的自动标签：记录用户手动移除的自动生成标签，避免后续换源/重算时再次自动生成。
 */
@Entity(tableName = "removedAutoTags")
data class RemovedAutoTag(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tagName: String = "",
    val createTime: Long = System.currentTimeMillis(),
) {
    override fun hashCode(): Int = id.hashCode()
    override fun equals(other: Any?): Boolean {
        if (other is RemovedAutoTag) return id == other.id
        return false
    }
}
