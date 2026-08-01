package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 藏书票 HTML 模板
 * 移植自 readdai `BookplateTemplate`。
 * 分组：书籍 / 统计 / 书摘 三大类
 */
@Entity(tableName = "bookplateTemplates")
data class BookplateTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val htmlContent: String = "",
    @ColumnInfo(defaultValue = "0") val isBuiltin: Boolean = false,
    @ColumnInfo(defaultValue = "0") val createTime: Long = 0L,
    @ColumnInfo(defaultValue = "0") val updateTime: Long = 0L,
    @ColumnInfo(defaultValue = "书籍") val groupName: String = DEFAULT_GROUP_BOOK,
) {
    companion object {
        const val DEFAULT_GROUP_BOOK = "书籍"
        const val DEFAULT_GROUP_STATS = "统计"
        const val DEFAULT_GROUP_ANNOTATION = "书摘"
    }
}
