package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

/**
 * 配音角色：多角色分配功能自己的角色档案。
 *
 * 身份 = (bookUrl, name)——一个人在一本书里只有一行、只有一个声音，
 * `poolLabel` 是这个人的**属性**（该用哪一路声音池），不再参与身份。
 * 唯一索引仍是 (bookUrl, name, poolLabel)：本 fork 的版本号跟着上游走，改索引属结构变更、
 * 已发布的库不会重跑迁移，所以「同名只留一行」由两处保证——写入侧按名字拒绝新建/改名
 * （`CastAssignmentStore.create`、`BookCastStore.createCharacter/updateCharacter/syncFromProfile`），
 * 折叠前的历史遗留由 [io.legado.app.help.readaloud.cast.CastIdentityFolder] 在进配音页时并掉。
 * 与多角色朗读/AI 链路共用的 [BookCharacterProfile] 区分：后者同样按 (bookUrl, name) 唯一，
 * 只多了头像/别名/简介那些「这个人是谁」的信息；分配弹层的候选、回填与状态更新一律读本表。
 * chapter_role_assignments.characterId 指向本表 id。
 */
@Entity(
    tableName = "cast_characters",
    indices = [
        Index(value = ["bookUrl", "name", "poolLabel"], unique = true),
        Index(value = ["bookUrl"]),
    ],
)
data class CastCharacter(
    @PrimaryKey
    val id: String,
    val bookUrl: String,
    val name: String,
    /** 声音池名（voice_pools.name，如「女青年」）：这个人**该用哪一路池**的属性，不参与身份。 */
    val poolLabel: String,
    /** 音色 id（read_aloud_voices.id），空 = 未选。 */
    val voiceId: String = "",
    /** 变声器预设名（voice_effect_presets.name），空 = 不变声。 */
    @ColumnInfo(defaultValue = "")
    val voiceEffect: String = "",
    /**
     * 「人物与角色配音」页的手动排序位（1 起，0 = 没排过）。
     *
     * 排过的行按它排；没排过的（新建、或老数据）排在后面，按男女主/男女配的档次默认置顶。
     */
    @ColumnInfo(defaultValue = "0")
    val sortOrder: Int = 0,
    /**
     * 这个角色自己的气泡：一条**只填了气泡那几栏**的 [HighlightRule] JSON
     * （bgImage / bgImageFit / bgImageScale / np* / manualNineSlice /
     * bgLengthOffsetLeft / bgLengthOffsetRight / bgColor）。
     *
     * 存成高亮规则的形状是为了不复写一套样式换算——正文那一份
     * `LegacyReaderStyleRangeMapper.toReaderStyle()` 直接吃它。空串 = 不设气泡。
     * 哪一句归这个角色由 chapter_role_assignments 给定，所以 pattern 与 targetScope 不参与。
     */
    @ColumnInfo(defaultValue = "")
    val bubbleRuleJson: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {

    /** [bubbleRuleJson] 解出来的那条规则；没设气泡、JSON 读坏了都返回 null。 */
    fun bubbleRule(): HighlightRule? = bubbleRuleJson.takeIf { it.isNotBlank() }?.let { json ->
        GSON.fromJsonObject<HighlightRule>(json).getOrNull()
    }
}
