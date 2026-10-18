package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 声音池分组：**独立实体**，可嵌套（parentId 指回本表，空串 = 根层），行为等同文件夹。
 *
 * 旧版是 `voice_pools.groupName` 那个纯字符串（只能一层、改名要全表改写）。
 * 首次进入声音池页时由 [io.legado.app.help.readaloud.cast.VoicePoolStore] 一次性升级成这里的行。
 */
@Entity(
    tableName = "voice_pool_groups",
    indices = [
        Index(value = ["parentId"]),
        Index(value = ["parentId", "name"], unique = true),
    ],
)
data class VoicePoolGroupEntity(
    @PrimaryKey
    val id: String,
    var name: String,
    /** 父分组 id，空串 = 根层。 */
    @ColumnInfo(defaultValue = "")
    var parentId: String = "",
    /** 同级内的手动顺序（长按拖动排序回写这里）。 */
    @ColumnInfo(defaultValue = "0")
    var order: Int = 0,
    /**
     * 组开关。关掉后整棵子树（含子组里的池）都不再作为**新建**角色分配的候选池；
     * 已经建好的角色不动，所以这里只影响候选清单，不影响已存数据。
     */
    @ColumnInfo(defaultValue = "1")
    var enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 声音池：音色的粗分类集合（男童/女少女/卡通人物…），用户可增删改。
 *
 * 已替代 fork 早期硬编码的音色分类枚举成为权威来源；分配角色悬浮窗的「声音池」下拉只列启用的池。
 * 默认 13 池首次使用时种子化（见 VoicePoolStore），全部可删除。
 */
@Entity(
    tableName = "voice_pools",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["groupId", "order"]),
        Index(value = ["groupName", "name"]),
    ],
)
data class VoicePoolEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    /** 所属分组 id，空串 = 未分组。 */
    @ColumnInfo(defaultValue = "")
    var groupId: String = "",
    /**
     * 旧的管理分组名，仅保留给备份/兼容读取：分组已改由 [groupId] 指向 [VoicePoolGroupEntity]。
     * 写操作会同步刷新成所属分组的显示路径，别让两者长期背离。
     */
    var groupName: String = "",
    /** 分组内手动顺序（长按拖动排序回写这里）。 */
    @ColumnInfo(defaultValue = "0")
    var order: Int = 0,
    val enabled: Boolean = true,
    /** 种子默认池标记（仅信息用途，不影响删除）。 */
    val isDefault: Boolean = false,
    /**
     * 池的性别分类，取值与 [BookCharacterProfile.ALL_VOICE_GENDERS] 一致：
     * `male` / `female` / `unknown`。默认池按池名固定映射；用户自建池默认 unknown。
     */
    @ColumnInfo(defaultValue = "unknown")
    var gender: String = VOICE_POOL_GENDER_UNKNOWN,
    /**
     * 池的年龄段分类，取值与 [BookCharacterProfile.ALL_VOICE_AGE_BANDS] 一致：
     * `child` / `teen` / `young_adult` / `adult` / `elderly` / `unknown`。
     * 「失配男/失配女」这类兜底池与「卡通人物」这类特殊池为 unknown。
     */
    @ColumnInfo(defaultValue = "unknown")
    var ageBand: String = VOICE_POOL_AGE_UNKNOWN,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val VOICE_POOL_GENDER_MALE = "male"
        const val VOICE_POOL_GENDER_FEMALE = "female"
        const val VOICE_POOL_GENDER_UNKNOWN = "unknown"

        const val VOICE_POOL_AGE_CHILD = "child"
        const val VOICE_POOL_AGE_TEEN = "teen"
        const val VOICE_POOL_AGE_YOUNG_ADULT = "young_adult"
        const val VOICE_POOL_AGE_ADULT = "adult"
        const val VOICE_POOL_AGE_ELDERLY = "elderly"
        const val VOICE_POOL_AGE_UNKNOWN = "unknown"
    }
}

/**
 * 音色 → 声音池归属（多对多）。
 *
 * 独立表而不给 read_aloud_voices 加列：音色行会被引擎同步 REPLACE 重建，
 * 成员关系必须与音色行生命周期解耦。
 */
@Entity(
    tableName = "voice_pool_members",
    primaryKeys = ["poolId", "voiceId"],
    indices = [Index(value = ["voiceId"]), Index(value = ["poolId"])],
)
data class VoicePoolMember(
    val poolId: String,
    val voiceId: String,
    /** 池内启用开关（展开池后的复选框）：停用不出现在分配角色音色菜单。 */
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
)
