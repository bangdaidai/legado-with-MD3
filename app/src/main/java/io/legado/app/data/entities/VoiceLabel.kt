package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * 用户给单个音色标的分类（性别 / 年龄段 / 风格标签）。
 *
 * 独立表而不给 [ReadAloudVoiceEntity] 加列：音色行由引擎同步逐字段 REPLACE 重建
 * （`SyncReadAloudVoicesUseCase` → `ReadAloudVoiceDao.upsertVoices`），挂在行上的用户标注
 * 会被那一次重建抹掉。与 [VoicePoolMember] 同一张独立的表、同一个理由。
 *
 * 三栏都允许为空 = 未标注。读取侧按「用户标注 > 池分类 > 引擎声明 > 名字启发」回落，
 * 所以这张表是**加法不是切换**：一行都没有时行为与引入它之前完全一致（S3 的回退口径）。
 */
@Entity(
    tableName = "voice_labels",
    primaryKeys = ["voiceId"],
)
data class VoiceLabel(
    /** 主键只在 `@Entity(primaryKeys = ...)` 上声明：字段再挂一个 `@PrimaryKey`，Room 会当成
     *  两处主键而直接报错（`You cannot have multiple primary keys defined`）。本仓库的
     *  `chapter_speech_segments`、`chapter_role_assignments` 也都是这一种写法。 */
    val voiceId: String,
    /** 取值见 [io.legado.app.help.readaloud.ReadAloudVoiceTraits]：`male` / `female`，空 = 未标注。 */
    @ColumnInfo(defaultValue = "")
    val gender: String = "",
    /** 取值见 [BookCharacterProfile.ALL_VOICE_AGE_BANDS]，空 = 未标注。 */
    @ColumnInfo(defaultValue = "")
    val ageBand: String = "",
    /** 用户自己写的风格标签，音色行的 `traitsJson` 里没有这一栏。 */
    @ColumnInfo(defaultValue = "[]")
    val tagsJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
)
