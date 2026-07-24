package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书籍标签分组实体类
 * 用于存储标签的分组信息（F1 标签系统）
 */
@Entity(
    tableName = "bookTagGroups",
    indices = [
        Index(value = ["name"], unique = true) // 确保分组名称唯一
    ]
)
data class BookTagGroup(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0, // 分组唯一ID，自动生成

    @ColumnInfo(name = "name")
    val name: String = "", // 分组名称

    @ColumnInfo(name = "sortOrder")
    val sortOrder: Int = 0, // 分组排序顺序

    @ColumnInfo(name = "createTime")
    val createTime: Long = System.currentTimeMillis(), // 分组创建时间

    @ColumnInfo(name = "updateTime")
    val updateTime: Long = System.currentTimeMillis() // 分组更新时间
) {
    // 用于创建新分组的辅助构造函数
    constructor(
        name: String,
        sortOrder: Int = 0,
        createTime: Long = System.currentTimeMillis()
    ) : this(
        id = 0, // 自动生成
        name = name,
        sortOrder = sortOrder,
        createTime = createTime,
        updateTime = createTime
    )
}
