package io.legado.app.help.readaloud.cast

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.data.entities.VoiceLabel
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray

/**
 * 用户对单个音色的分类标注（性别 / 年龄段 / 风格）的读写口径。
 *
 * 为什么要单独一张表：音色行的 `traitsJson` 由引擎同步逐字段 REPLACE 重建
 * （`SyncReadAloudVoicesUseCase`），把标注写在音色行上等于每次同步就丢一次；
 * 而 `traitsJson` 里本来也**没有年龄段**这一栏——年龄只能从「属于哪个声音池」派生。
 *
 * 读取侧的优先级在这里定死（S3④）：**用户标注 > 池分类 > 引擎声明 > 名字启发**，
 * 且**逐栏**覆盖：只标了性别没标年龄时，年龄仍走池分类那条路。
 * 表里没有行 = 未标注 = 行为与引入这张表之前完全一致，所以这是加法不是切换。
 */
object VoiceLabelStore {

    /** 年龄段合法取值：档案那一套枚举 + 空串（未标注）。 */
    private val AGE_BANDS = BookCharacterProfile.ALL_VOICE_AGE_BANDS.toSet() + ""

    /** 性别合法取值：音色 traits 那一套 + 未标注（界面允许把声明的性别"退回未标注"）。 */
    private val GENDERS = setOf("", "male", "female")

    /** 全表：音色列表要按标注给每行挂标签，逐行查不如一次取完。 */
    suspend fun all(): Map<String, VoiceLabel> = runCatching {
        appDb.voiceLabelDao.getAll().associateBy { it.voiceId }
    }.onFailure {
        // 表不存在（老库没跑过这条建表语句）时不能把整个音色页带崩：
        // 按"没有任何标注"继续，读取侧退回池分类与引擎声明。
        AppLog.putDebug("读取音色标注失败，按未标注处理：${it.localizedMessage}")
    }.getOrDefault(emptyMap())

    suspend fun get(voiceId: String): VoiceLabel? = runCatching {
        appDb.voiceLabelDao.get(voiceId)
    }.getOrNull()

    /**
     * 保存一次标注。三栏全空 = 删行，不保留一条"空标注"。
     *
     * 非法取值直接拒绝（不静默归一）：界面上给的是枚举，出现非法值说明调用方写错了，
     * 悄悄丢掉会让用户以为"标上了但没生效"。
     */
    suspend fun save(voiceId: String, gender: String, ageBand: String, tags: List<String>): Boolean {
        if (voiceId.isBlank()) return false
        val g = gender.trim()
        val a = ageBand.trim()
        if (g !in GENDERS || a !in AGE_BANDS) return false
        val cleanTags = tags.map(String::trim).filter(String::isNotEmpty).distinct()
        val dao = appDb.voiceLabelDao
        if (g.isEmpty() && a.isEmpty() && cleanTags.isEmpty()) {
            dao.delete(voiceId)
            return true
        }
        val existing = runCatching { dao.get(voiceId) }.getOrNull()
        dao.upsert(
            VoiceLabel(
                voiceId = voiceId,
                gender = g,
                ageBand = a,
                tagsJson = GSON.toJson(cleanTags),
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    /** 音色被删掉时随行清理，避免标注表只增不减。 */
    suspend fun onVoiceDeleted(voiceId: String) {
        runCatching { appDb.voiceLabelDao.delete(voiceId) }
    }

    /** 标注里的风格标签；没有行或没有标签都给空表。 */
    fun tagsOf(label: VoiceLabel?): List<String> =
        GSON.fromJsonArray<String>(label?.tagsJson.orEmpty()).getOrNull().orEmpty()
            .map(String::trim).filter(String::isNotEmpty)

    /**
     * 输入框那一串标签 → 标签列表。中英文逗号、顿号都算分隔符：
     * 用户在中文输入法下打出来的通常是「，」，只认「,」会让整串变成一个标签。
     */
    fun parseTags(text: String): List<String> =
        text.split(',', '，', '、').map(String::trim).filter(String::isNotEmpty).distinct()

    /**
     * voiceId → (性别, 年龄段)，用户标注逐栏优先于池分类派生的结果。
     *
     * 这是自动选音唯一的年龄/性别来源：调用方不再各拿一半（以前只取年龄段、性别丢掉，
     * 见 S3②），也不再按池成员顺序"第一个有分类的池"说了算。
     */
    suspend fun classificationMap(): Map<String, Pair<String, String>> {
        val fromPools = VoicePoolStore.voiceClassificationMap()
        val labels = all().filterValues { it.gender.isNotBlank() || it.ageBand.isNotBlank() }
        if (labels.isEmpty()) return fromPools
        val result = fromPools.toMutableMap()
        labels.forEach { (voiceId, label) ->
            val declared = fromPools[voiceId]
            val gender = label.gender.trim().ifEmpty { declared?.first.orEmpty() }
            val ageBand = label.ageBand.trim().ifEmpty { declared?.second.orEmpty() }
            if (gender.isNotEmpty() || ageBand.isNotEmpty()) result[voiceId] = gender to ageBand
        }
        return result
    }

    /**
     * 单条音色的标注摘要，给音色行与编辑弹窗回显用。
     *
     * [userLabeled] 只在"这一栏真的是用户标的"时为 true：界面据此区分实字（用户标的）
     * 与灰字（引擎声明或名字启发预填的提示），不硬猜成用户的意思。
     */
    data class Summary(
        val gender: String = "",
        val ageBand: String = "",
        val tags: List<String> = emptyList(),
        val userLabeled: Boolean = false,
    )

    fun summary(label: VoiceLabel?): Summary {
        if (label == null) return Summary()
        val tags = tagsOf(label)
        val gender = label.gender.trim()
        val ageBand = label.ageBand.trim()
        // 只存了标签、性别年龄都留空的行也算"用户标过"——那批标签是他写的
        return Summary(gender, ageBand, tags, gender.isNotEmpty() || ageBand.isNotEmpty() || tags.isNotEmpty())
    }
}
