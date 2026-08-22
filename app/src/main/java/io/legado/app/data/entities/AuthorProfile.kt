package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 作者简介。以作者名为主键，与书架/阅读记忆解耦，删书不影响已写好的简介。
 * [source] 区分人工撰写与 AI 生成，批量生成时据此跳过用户手写的内容。
 */
@Entity(tableName = "authorProfiles")
data class AuthorProfile(
    @PrimaryKey val name: String = "",
    val bio: String = "",
    @ColumnInfo(defaultValue = "manual") val source: String = SOURCE_MANUAL,
    @ColumnInfo(defaultValue = "0") val updateTime: Long = 0L,
    /** AI 生成时记录所用模型，人工撰写为 null */
    val model: String? = null,
) {
    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_AI = "ai"
    }
}
