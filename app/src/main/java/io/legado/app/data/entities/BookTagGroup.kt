package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 标签分组：用于把标签归类（如「玄幻」「都市」），与书架分组(BookGroup)相互独立。
 */
@Entity(tableName = "bookTagGroups")
data class BookTagGroup(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "",
    val sortOrder: Int = 0,
) {
    override fun hashCode(): Int = id.hashCode()
    override fun equals(other: Any?): Boolean {
        if (other is BookTagGroup) return id == other.id
        return false
    }
}
