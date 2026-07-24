package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书籍标签实体类（F1 标签系统）
 * 用于存储可复用的标签库：每个标签有名称、颜色、所属分组。
 */
@Entity(
    tableName = "bookTags",
    indices = [
        Index(value = ["name"], unique = true), // 标签名称唯一
        Index(value = ["groupId"]) // 提高按分组查询的性能
    ]
)
data class BookTag(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0, // 标签唯一ID，自动生成

    @ColumnInfo(name = "name")
    val name: String = "", // 标签名称，如"言情"、"悬疑"等

    @ColumnInfo(name = "color")
    val color: Int = 0, // 标签颜色（ARGB）

    @ColumnInfo(name = "groupId", defaultValue = "0")
    val groupId: Long = 0, // 标签分组ID，0表示未分组

    @ColumnInfo(name = "createTime")
    val createTime: Long = System.currentTimeMillis(), // 标签创建时间

    @ColumnInfo(name = "updateTime")
    val updateTime: Long = System.currentTimeMillis() // 标签更新时间
) {
    // 用于创建新标签的辅助构造函数
    constructor(
        name: String,
        color: Int,
        groupId: Long = 0,
        createTime: Long = System.currentTimeMillis()
    ) : this(
        id = 0, // 自动生成
        name = name,
        color = color,
        groupId = groupId,
        createTime = createTime,
        updateTime = createTime
    )
}
