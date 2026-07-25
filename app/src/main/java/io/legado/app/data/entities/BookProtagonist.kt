package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 书籍主角（人设）表 —— 对齐 readdai 的 BookProtagonist。
 *
 * 与 readdai 保持同构：bookUrl + name 唯一约束、isCustom 标记是否手动添加。
 * 主角提取逻辑见 [io.legado.app.help.book.ProtagonistExtractor]。
 */
@Entity(
    tableName = "bookProtagonists",
    indices = [Index(value = ["bookUrl", "name"], unique = true)],
)
data class BookProtagonist(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "bookUrl")
    val bookUrl: String = "",

    @ColumnInfo(name = "name")
    val name: String = "",

    @ColumnInfo(name = "isCustom", defaultValue = "0")
    val isCustom: Boolean = false,

    @ColumnInfo(name = "createTime")
    val createTime: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updateTime")
    val updateTime: Long = System.currentTimeMillis(),
)
