package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 被用户手动移除的自动标签实体类（F1 标签系统）
 * 记录用户不希望再自动生成的标签名称。
 * 与 readdai 同名实体对齐（name + type）。
 */
@Entity(tableName = "removedAutoTags")
data class RemovedAutoTag(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "type")
    val type: Int = 0,

    @ColumnInfo(name = "createTime")
    val createTime: Long = System.currentTimeMillis()
)
