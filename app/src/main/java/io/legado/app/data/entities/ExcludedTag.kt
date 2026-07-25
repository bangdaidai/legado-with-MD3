package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 排除标签实体类（F1 标签系统）
 * 记录用户不想在书架/标签选择中显示的标签名称。
 * isRegex=true 时，name 字段作为正则表达式为标签名做匹配（如 \d+ 排除所有带数字的标签）。
 * 与 readdai 同名实体对齐。
 */
@Entity(tableName = "excludedTags")
data class ExcludedTag(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "isRegex", defaultValue = "0")
    val isRegex: Boolean = false,

    @ColumnInfo(name = "createTime")
    val createTime: Long = System.currentTimeMillis()
)
