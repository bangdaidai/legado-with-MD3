package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书籍标签关联实体类（F1 标签系统）
 * 多对多关系：一本书可关联多个标签，一个标签可关联多本书。
 */
@Entity(
    tableName = "bookTagRelations",
    indices = [
        Index(value = ["bookUrl"]),
        Index(value = ["tagId"]),
        Index(value = ["bookUrl", "tagId"], unique = true)
    ]
)
data class BookTagRelation(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = "", // 关联关系唯一ID，格式如"relation_时间戳_随机数"

    @ColumnInfo(name = "bookUrl")
    val bookUrl: String = "", // 书籍URL，与 Book 实体关联

    @ColumnInfo(name = "tagId")
    val tagId: Long = 0, // 标签ID，与 BookTag 实体关联

    @ColumnInfo(name = "createTime")
    val createTime: Long = System.currentTimeMillis() // 关联关系创建时间
) {
    companion object {
        fun generateId(): String =
            "relation_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}
