package io.legado.app.help.readaloud.cast

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.data.entities.ReadAloudVoiceEntity
import io.legado.app.data.entities.VoicePoolEntity
import io.legado.app.data.entities.VoicePoolGroupEntity
import io.legado.app.data.entities.VoicePoolMember
import io.legado.app.data.repository.toDomain
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.help.readaloud.ReadAloudVoiceTraits
import io.legado.app.help.readaloud.VoiceTraits
import org.koin.core.context.GlobalContext
import java.util.UUID

/**
 * 声音池管理的数据出口（多角色规则 → 声音池 tab 的权威读写）。
 *
 * UI/ViewModel 一律经本 Store 访问 DAO（架构护栏）。
 * 默认 13 池在首次使用时种子化（prefs 标记，用户删光也不会复活）。
 */
object VoicePoolStore {

    /**
     * 种子默认池（名字 → 管理分组 → 性别 → 年龄段），与参考软件 MultiTTS 的默认声音池一致。
     *
     * [gender] / [ageBand] 是池的结构化分类，是「角色年龄性别 → 音色」精确匹配的唯一桥梁
     * （音色本身没有年龄字段，年龄从池成员关系派生）。「失配男/失配女」与「卡通人物」
     * 这类兜底/特殊池为 unknown——它们不代表某个年龄段。
     */
    private data class SeedPool(
        val name: String,
        val group: String,
        val gender: String,
        val ageBand: String,
    )

    private val DEFAULT_POOLS: List<SeedPool> = listOf(
        SeedPool("男童", "男", VoicePoolEntity.VOICE_POOL_GENDER_MALE, VoicePoolEntity.VOICE_POOL_AGE_CHILD),
        SeedPool("男少年", "男", VoicePoolEntity.VOICE_POOL_GENDER_MALE, VoicePoolEntity.VOICE_POOL_AGE_TEEN),
        SeedPool("男青年", "男", VoicePoolEntity.VOICE_POOL_GENDER_MALE, VoicePoolEntity.VOICE_POOL_AGE_YOUNG_ADULT),
        SeedPool("男中年", "男", VoicePoolEntity.VOICE_POOL_GENDER_MALE, VoicePoolEntity.VOICE_POOL_AGE_ADULT),
        SeedPool("男性老年", "男", VoicePoolEntity.VOICE_POOL_GENDER_MALE, VoicePoolEntity.VOICE_POOL_AGE_ELDERLY),
        SeedPool("失配男", "男", VoicePoolEntity.VOICE_POOL_GENDER_MALE, VoicePoolEntity.VOICE_POOL_AGE_UNKNOWN),
        SeedPool("女童", "女", VoicePoolEntity.VOICE_POOL_GENDER_FEMALE, VoicePoolEntity.VOICE_POOL_AGE_CHILD),
        SeedPool("女少女", "女", VoicePoolEntity.VOICE_POOL_GENDER_FEMALE, VoicePoolEntity.VOICE_POOL_AGE_TEEN),
        SeedPool("女青年", "女", VoicePoolEntity.VOICE_POOL_GENDER_FEMALE, VoicePoolEntity.VOICE_POOL_AGE_YOUNG_ADULT),
        SeedPool("女中年", "女", VoicePoolEntity.VOICE_POOL_GENDER_FEMALE, VoicePoolEntity.VOICE_POOL_AGE_ADULT),
        SeedPool("女性老年", "女", VoicePoolEntity.VOICE_POOL_GENDER_FEMALE, VoicePoolEntity.VOICE_POOL_AGE_ELDERLY),
        SeedPool("失配女", "女", VoicePoolEntity.VOICE_POOL_GENDER_FEMALE, VoicePoolEntity.VOICE_POOL_AGE_UNKNOWN),
        SeedPool("卡通人物", "特殊", VoicePoolEntity.VOICE_POOL_GENDER_UNKNOWN, VoicePoolEntity.VOICE_POOL_AGE_UNKNOWN),
    )

    private const val PREFS = "cast_prefs"
    private const val KEY_SEEDED = "voicePoolsSeeded"
    private const val KEY_GROUPS_SEEDED = "voicePoolGroupsSeeded"

    /** 存量成员表收成「一音色一组」只做一次（报告进日志，见 [foldDuplicateMembers]）。 */
    private const val KEY_MEMBERS_FOLDED = "voicePoolMembersFolded"
    private const val MAX_NAME = 12
    private const val MAX_GROUP_NAME = 12

    /** 分组显示路径的分隔符；也用于兼容旧的 `voice_pools.groupName` 字符串。 */
    const val GROUP_SEPARATOR = "/"

    /** 系统 TTS 的 engineId 前缀，「发现音色」存的全名，目录同步存的是裸包名。 */
    private const val SYSTEM_ENGINE_PREFIX = "system:"

    private fun prefs(): android.content.SharedPreferences =
        GlobalContext.get().get<Context>().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 幂等种子化：只执行一次（prefs 标记），用户删光默认池后不会复活。 */
    suspend fun ensureSeeded() {
        if (prefs().getBoolean(KEY_SEEDED, false)) return
        for (seed in DEFAULT_POOLS) {
            if (appDb.voicePoolDao.getByName(seed.name) == null) {
                appDb.voicePoolDao.insertIgnore(
                    VoicePoolEntity(
                        id = UUID.randomUUID().toString(),
                        name = seed.name,
                        groupName = seed.group,
                        isDefault = true,
                        gender = seed.gender,
                        ageBand = seed.ageBand,
                    ),
                )
            }
        }
        prefs().edit().putBoolean(KEY_SEEDED, true).apply()
    }

    /**
     * 一次性把旧的 `groupName` 字符串升级成 [VoicePoolGroupEntity] 行（幂等，prefs 标记）。
     *
     * 旧值可能已经是 `父/子` 路径，按分隔符逐级建组；同级 order 按名称序递增，
     * 保证升级后列表顺序与升级前肉眼所见一致。
     */
    suspend fun ensureGroups() {
        if (prefs().getBoolean(KEY_GROUPS_SEEDED, false)) return
        ensureSeeded()
        val dao = appDb.voicePoolDao
        if (dao.getGroups().isEmpty()) {
            val idByPath = mutableMapOf<String, String>()
            val pending = mutableListOf<VoicePoolGroupEntity>()
            val nextOrder = mutableMapOf<String, Int>()
            fun ensureGroup(path: String): String {
                idByPath[path]?.let { return it }
                val cut = path.lastIndexOf(GROUP_SEPARATOR)
                val parentId = if (cut < 0) "" else ensureGroup(path.substring(0, cut))
                val id = UUID.randomUUID().toString()
                pending += VoicePoolGroupEntity(
                    id = id,
                    name = path.substringAfterLast(GROUP_SEPARATOR),
                    parentId = parentId,
                    order = (nextOrder[parentId] ?: 0) + 1,
                )
                nextOrder[parentId] = (nextOrder[parentId] ?: 0) + 1
                idByPath[path] = id
                return id
            }
            val pools = dao.getAll().sortedWith(compareBy({ it.groupName }, { it.name }))
            val poolOrder = mutableMapOf<String, Int>()
            val changed = pools.mapNotNull { pool ->
                val path = pool.groupName.trim()
                if (path.isEmpty()) return@mapNotNull null
                val groupId = ensureGroup(path)
                val next = (poolOrder[groupId] ?: 0) + 1
                poolOrder[groupId] = next
                pool.copy(groupId = groupId, order = next)
            }
            pending.forEach { dao.insertGroup(it) }
            if (changed.isNotEmpty()) dao.updatePools(changed)
        }
        prefs().edit().putBoolean(KEY_GROUPS_SEEDED, true).apply()
    }

    /** 分组树（DFS 拉平，带 depth/path），根层在前、子组紧跟其父。 */
    suspend fun listGroups(): List<CastGroupRow> {
        ensureGroups()
        val all = appDb.voicePoolDao.getGroups()
        // 父组不存在的孤儿按根层处理：不然它会整棵从页面上消失，看起来像数据丢了
        val ids = all.mapTo(mutableSetOf()) { it.id }
        val byParent = all.groupBy { if (it.parentId in ids) it.parentId else "" }
            .mapValues { (_, rows) -> rows.sortedWith(compareBy({ it.order }, { it.name })) }
        val rows = ArrayList<CastGroupRow>(all.size)
        fun walk(group: VoicePoolGroupEntity, depth: Int, parentPath: String, parentUsable: Boolean) {
            val path = if (parentPath.isEmpty()) group.name else "$parentPath$GROUP_SEPARATOR${group.name}"
            val usable = parentUsable && group.enabled
            rows += CastGroupRow(group.id, group.name, group.parentId, path, depth, group.order, group.enabled, usable)
            byParent[group.id].orEmpty().forEach { walk(it, depth + 1, path, usable) }
        }
        byParent[""].orEmpty().forEach { walk(it, 0, "", true) }
        return rows
    }

    /** groupId → 显示路径（含所有组，用于下拉候选与 groupName 兼容字段）。 */
    private suspend fun pathByGroupId(): Map<String, String> =
        listGroups().associate { it.id to it.path }

    suspend fun listPools(): List<CastPoolRow> {
        ensureGroups()
        val groups = listGroups()
        val pathByGroup = groups.associate { it.id to it.path }
        val usableByGroup = groups.associate { it.id to it.usable }
        // 音色在「朗读引擎与音色」里被删掉后，成员行会留在表里；计数只按还在的音色算，
        // 否则会出现 (4/4) 却一个成员都列不出来的情况。
        val alive = appDb.readAloudVoiceDao.getVoices()
            .filter { !it.excluded }
            .mapTo(mutableSetOf()) { it.id }
        val members = appDb.voicePoolDao.getAllMembers().filter { it.voiceId in alive }
        val totalByPool = members.groupingBy { it.poolId }.eachCount()
        val enabledByPool = members
            .filter { it.enabled }
            .groupingBy { it.poolId }
            .eachCount()
        return appDb.voicePoolDao.getAll().map {
            CastPoolRow(
                id = it.id,
                name = it.name,
                groupId = it.groupId,
                groupName = pathByGroup[it.groupId].orEmpty(),
                order = it.order,
                enabled = it.enabled,
                groupEnabled = usableByGroup[it.groupId] ?: true,
                isDefault = it.isDefault,
                total = totalByPool[it.id] ?: 0,
                enabledCount = enabledByPool[it.id] ?: 0,
                gender = it.gender,
                ageBand = it.ageBand,
            )
        }
    }

    /** 展开池：全部音色列表 + 归属/启用状态（分组头展开用）。 */
    suspend fun poolDetail(poolId: String): CastPoolDetail {
        ensureSeeded()
        val voices = appDb.readAloudVoiceDao.getVoices().filter { !it.excluded }
        val engineNames = engineNames(voices)
        val members = appDb.voicePoolDao.getMembers(poolId).associateBy { it.voiceId }
        val pools = listPools()
        return CastPoolDetail(
            pool = pools.first { it.id == poolId },
            members = voices
                .filter { members.containsKey(it.id) }
                .map {
                    CastMemberRow(
                        id = it.id,
                        displayName = it.displayName,
                        engineName = engineNames[it.id].orEmpty(),
                        enabled = members.getValue(it.id).enabled,
                    )
                },
        )
    }

    /**
     * 音色 → 所属引擎名，和「朗读引擎与音色」页那行副标题同源。
     *
     * 只查库，不建 TextToSpeech 实例：系统引擎的显示名在目录同步时已经作为
     * `speakerId` 为空的那一行写进 [ReadAloudVoice] 表了，直接拿它当字典用。
     * 音色的 `engineId` 有两种写法（裸包名和 `system:` 前缀），两种都要能查到。
     */
    suspend fun voiceEngineNames(): Map<String, String> =
        engineNames(appDb.readAloudVoiceDao.getVoices())

    private suspend fun engineNames(voices: List<ReadAloudVoiceEntity>): Map<String, String> {
        val systemNames = voices
            .filter {
                it.engineType == ReadAloudVoice.ENGINE_SYSTEM &&
                    it.speakerId.isBlank() &&
                    it.managedBy == ReadAloudVoice.MANAGED_BY_CONFIGURED_TTS
            }
            .associate { it.engineId to it.displayName }
        val cloudNames = appDb.cloudTtsEngineDao.getAll().associate { it.id to it.name }
        val httpNames = appDb.httpTTSDao.all.associate { it.id.toString() to it.name }
        return voices.associate { voice ->
            val engineId = voice.engineId
            // 系统音色没有具体引擎包名时 engineId 为空串，正好对上目录里那条
            // 「系统朗读引擎」行（它的 engineId 也是空串）。
            val name = when (voice.engineType) {
                ReadAloudVoice.ENGINE_SYSTEM ->
                    systemNames[engineId.removePrefix(SYSTEM_ENGINE_PREFIX)]
                ReadAloudVoice.ENGINE_CLOUD -> cloudNames[engineId]
                ReadAloudVoice.ENGINE_HTTP -> httpNames[engineId]
                else -> null
            }
            voice.id to (name ?: engineId)
        }
    }

    /** 池内音色启用开关（复选框）。 */
    suspend fun setMemberEnabled(poolId: String, voiceId: String, enabled: Boolean) {
        appDb.voicePoolDao.setMemberEnabled(poolId, voiceId, enabled)
    }

    /** 分配角色悬浮窗 / AI 分配用：能作为新建分配候选的池名（池自己开 + 分组链全开）。 */
    suspend fun enabledPoolNames(): List<String> {
        ensureSeeded()
        return listPools().filter { it.usable }.map { it.name }
    }

    /**
     * 人物档案 voiceAgeBand 列里的池名。
     *
     * 该列现在存声音池名，老数据里存的是官方年龄段枚举（child/teen/…）：
     * 那些值不是用户建过的池，一律按未分配处理，映射成某个池等于替用户编一个池。
     */
    fun poolNameOrEmpty(raw: String?): String = raw?.trim()
        ?.takeUnless { it.isEmpty() || it in BookCharacterProfile.ALL_VOICE_AGE_BANDS }
        .orEmpty()

    /** voiceId → 所属池名集合（停用的池/组、已删掉的音色都不算）。 */
    suspend fun voicePoolMap(): Map<String, Set<String>> {
        ensureSeeded()
        val pools = listPools().filter { it.usable }.associate { it.id to it.name }
        if (pools.isEmpty()) return emptyMap()
        val alive = appDb.readAloudVoiceDao.getVoices()
            .filter { !it.excluded }
            .mapTo(mutableSetOf()) { it.id }
        return appDb.voicePoolDao.getAllMembers()
            .filter { it.enabled && it.voiceId in alive }
            .mapNotNull { m -> pools[m.poolId]?.let { m.voiceId to it } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }
    }

    /**
     * 池的结构化分类（性别 + 年龄段）；池不存在或分类为 unknown 时返回 null。
     *
     * 这是「角色年龄性别 → 音色」精确匹配链路的读取端：调用方拿到后可以判断
     * 「这个池代表女·青年」，而不必解析池名字符串。
     */
    suspend fun poolClassification(poolId: String): Pair<String, String>? {
        ensureSeeded()
        val pool = appDb.voicePoolDao.getAll().firstOrNull { it.id == poolId } ?: return null
        return poolClassificationOf(pool)
    }

    /** 按名取池（含停用的）；写入点用来把池名解析成 id + ageBand。 */
    suspend fun poolByName(name: String): VoicePoolEntity? {
        ensureSeeded()
        return appDb.voicePoolDao.getByName(name.trim())
    }

    /**
     * 档案对应的声音池名（读取端统一入口）。
     *
     * 优先用 [BookCharacterProfile.voicePoolId] 精确查池（Phase 3 之后的写入都带外键）；
     * 外键为空时回落到把 `voiceAgeBand` 当池名解析——兼容迁移前的历史行、以及
     * 还没改完的写入点（如 AI 识别仍把池名写进 voiceAgeBand）。
     */
    suspend fun poolLabelOf(profile: BookCharacterProfile): String {
        ensureSeeded()
        profile.voicePoolId.trim().takeIf { it.isNotEmpty() }?.let { poolId ->
            appDb.voicePoolDao.getAll().firstOrNull { it.id == poolId }?.let { return it.name }
        }
        return poolNameOrEmpty(profile.voiceAgeBand)
    }

    /**
     * voiceId → (性别, 年龄段)，从**启用池的成员关系**派生。
     *
     * 音色本身没有年龄字段，年龄从「属于哪个池」推导。成员表已收成「一音色一组」
     * （[foldDuplicateMembers]），所以这里 `putIfAbsent` 只是兜底，不再决定结果。
     * 全在无分类池里的音色不出现在结果里（调用方按 unknown 兜底，走关键词匹配）。
     *
     * **用户自己标的标签优先于这里的结果**，合并发生在
     * [VoiceLabelStore.classificationMap]（S3④ 定死的口径），选音只认那一处。
     */
    suspend fun voiceClassificationMap(): Map<String, Pair<String, String>> {
        foldDuplicateMembers()
        val classifiedPools = appDb.voicePoolDao.getAll()
            .filter { it.enabled }
            .mapNotNull { pool -> poolClassificationOf(pool)?.let { pool.id to it } }
            .toMap()
        if (classifiedPools.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, Pair<String, String>>()
        appDb.voicePoolDao.getAllMembers()
            .filter { it.enabled }
            .forEach { member ->
                val classification = classifiedPools[member.poolId] ?: return@forEach
                // 已被更明确的池分类过就不覆盖（保持"第一个有分类的池"语义）
                result.putIfAbsent(member.voiceId, classification)
            }
        return result
    }

    /** 池的结构化分类判定；internal 供单测。gender/ageBand 全 unknown（或空）返回 null。 */
    internal fun poolClassificationOf(pool: VoicePoolEntity): Pair<String, String>? {
        val gender = pool.gender.trim()
        val ageBand = pool.ageBand.trim()
        val hasGender = gender.isNotEmpty() && gender != VoicePoolEntity.VOICE_POOL_GENDER_UNKNOWN
        val hasAgeBand = ageBand.isNotEmpty() && ageBand != VoicePoolEntity.VOICE_POOL_AGE_UNKNOWN
        return if (hasGender || hasAgeBand) gender to ageBand else null
    }

    /**
     * 池名 → 池内可用音色 id（顺序稳定：按音色显示名排序，与成员页一致）。
     *
     * 池不存在或池本身停用给空列表；自动选音用它，所以只取 enabled 的成员，
     * 且只取朗读设置里真正可用的音色。
     */
    suspend fun enabledVoiceIdsOfPool(poolName: String): List<String> {
        ensureSeeded()
        val pool = appDb.voicePoolDao.getByName(poolName)?.takeIf { it.enabled } ?: return emptyList()
        val members = appDb.voicePoolDao.getMembers(pool.id).filter { it.enabled }.map { it.voiceId }
        if (members.isEmpty()) return emptyList()
        return appDb.readAloudVoiceDao.getEnabledVoices()
            .map { it.id }
            .filter { members.contains(it) }
    }

    /** 名字全局唯一；非法/重名返回 false。[groupId] 空串 = 未分组。 */
    suspend fun createPool(
        name: String,
        groupId: String,
        gender: String,
        ageBand: String,
    ): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n.length > MAX_NAME) return false
        if (!isClassifiable(gender, ageBand)) return false
        ensureGroups()
        if (appDb.voicePoolDao.getByName(n) != null) return false
        val dao = appDb.voicePoolDao
        dao.insert(
            VoicePoolEntity(
                id = UUID.randomUUID().toString(),
                name = n,
                groupId = groupId,
                groupName = pathByGroupId()[groupId].orEmpty(),
                order = nextPoolOrder(groupId),
                gender = gender.trim(),
                ageBand = ageBand.trim(),
            ),
        )
        return true
    }

    /** 改名/换组/改分类；改名为已存在的其它池名时失败。 */
    suspend fun updatePool(
        id: String,
        name: String,
        groupId: String,
        gender: String,
        ageBand: String,
    ): Boolean {
        val dao = appDb.voicePoolDao
        val pool = dao.getAll().firstOrNull { it.id == id } ?: return false
        val n = name.trim()
        if (n.isEmpty() || n.length > MAX_NAME) return false
        if (!isClassifiable(gender, ageBand)) return false
        val other = dao.getByName(n)
        if (other != null && other.id != id) return false
        val nextGender = gender.trim()
        val nextAgeBand = ageBand.trim()
        if (pool.groupId != groupId) {
            dao.update(
                pool.copy(
                    name = n,
                    groupId = groupId,
                    groupName = pathByGroupId()[groupId].orEmpty(),
                    order = nextPoolOrder(groupId),
                    gender = nextGender,
                    ageBand = nextAgeBand,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        } else {
            dao.update(
                pool.copy(
                    name = n,
                    gender = nextGender,
                    ageBand = nextAgeBand,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        return true
    }

    /**
     * 池分类的合法取值校验：只接受枚举本身或 `unknown`（未分类），不接受空串以外的自由文本。
     *
     * 非法值返回 false 让调用方当场失败，不静默归一成 unknown——界面给的是枚举，
     * 走到非法值说明调用方写错了，归一会把"没生效"伪装成"生效了"。
     */
    fun isClassifiable(gender: String, ageBand: String): Boolean =
        gender.trim() in CLASSIFIABLE_GENDERS && ageBand.trim() in CLASSIFIABLE_AGE_BANDS

    private val CLASSIFIABLE_GENDERS = setOf(
        VoicePoolEntity.VOICE_POOL_GENDER_MALE,
        VoicePoolEntity.VOICE_POOL_GENDER_FEMALE,
        VoicePoolEntity.VOICE_POOL_GENDER_UNKNOWN,
    )

    private val CLASSIFIABLE_AGE_BANDS = setOf(
        VoicePoolEntity.VOICE_POOL_AGE_CHILD,
        VoicePoolEntity.VOICE_POOL_AGE_TEEN,
        VoicePoolEntity.VOICE_POOL_AGE_YOUNG_ADULT,
        VoicePoolEntity.VOICE_POOL_AGE_ADULT,
        VoicePoolEntity.VOICE_POOL_AGE_ELDERLY,
        VoicePoolEntity.VOICE_POOL_AGE_UNKNOWN,
    )

    private suspend fun nextPoolOrder(groupId: String): Int =
        (appDb.voicePoolDao.getAll().filter { it.groupId == groupId }.maxOfOrNull { it.order } ?: 0) + 1

    suspend fun deletePool(id: String) {
        appDb.voicePoolDao.clearMembers(id)
        appDb.voicePoolDao.deleteById(id)
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val pool = appDb.voicePoolDao.getAll().firstOrNull { it.id == id } ?: return
        appDb.voicePoolDao.update(pool.copy(enabled = enabled))
    }

    /** 组开关：关掉后整棵子树不再出现在新建分配的候选池里，已建好的角色不动。 */
    suspend fun setGroupEnabled(groupId: String, enabled: Boolean) {
        val dao = appDb.voicePoolDao
        dao.getGroup(groupId)?.let {
            dao.updateGroups(
                listOf(it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
            )
        }
    }

    /** 新建分组；同级重名/非法名返回 false。[parentId] 空串 = 根层。 */
    suspend fun createGroup(name: String, parentId: String): Boolean {
        val n = name.trim()
        if (!validGroupName(n)) return false
        ensureGroups()
        val dao = appDb.voicePoolDao
        val siblings = dao.getGroups().filter { it.parentId == parentId }
        if (siblings.any { it.name == n }) return false
        dao.insertGroup(
            VoicePoolGroupEntity(
                id = UUID.randomUUID().toString(),
                name = n,
                parentId = parentId,
                order = (siblings.maxOfOrNull { it.order } ?: 0) + 1,
            ),
        )
        return true
    }

    suspend fun renameGroup(groupId: String, newGroup: String): Boolean {
        val n = newGroup.trim()
        if (!validGroupName(n)) return false
        val dao = appDb.voicePoolDao
        val group = dao.getGroup(groupId) ?: return false
        if (dao.getGroups().any { it.parentId == group.parentId && it.name == n && it.id != groupId }) {
            return false
        }
        dao.updateGroups(listOf(group.copy(name = n, updatedAt = System.currentTimeMillis())))
        return syncGroupPaths()
    }

    /**
     * 删除分组（解散）：连子组带池一起上提到被删组的父级，组本身消失。
     *
     * 与旧行为（清空组名）不同——分组现在是真实体，删掉后里面的东西得有地方去。
     */
    suspend fun dissolveGroup(groupId: String): Boolean {
        val dao = appDb.voicePoolDao
        val group = dao.getGroup(groupId) ?: return false
        val parent = group.parentId
        val now = System.currentTimeMillis()
        dao.getGroups().filter { it.parentId == groupId }.forEach {
            dao.setGroupParent(it.id, parent, now)
        }
        val pools = dao.getAll().filter { it.groupId == groupId }
        var order = (pools.map { it.order }.maxOrNull() ?: 0)
        val moved = pools.map {
            order += 1
            it.copy(groupId = parent, order = order)
        }
        if (moved.isNotEmpty()) dao.updatePools(moved)
        dao.deleteGroupById(groupId)
        return syncGroupPaths()
    }

    /**
     * 换父级（菜单「移动到」）。禁止挂到自己或自己的子孙下面，否则树成环。
     */
    suspend fun moveGroupToParent(groupId: String, parentId: String): Boolean {
        if (groupId == parentId) return false
        val dao = appDb.voicePoolDao
        val group = dao.getGroup(groupId) ?: return false
        if (parentId.isNotEmpty()) {
            var cursor: String? = parentId
            var guard = 0
            while (cursor != null && cursor.isNotEmpty() && guard++ < 32) {
                if (cursor == groupId) return false
                cursor = dao.getGroup(cursor)?.parentId
            }
        }
        if (group.parentId == parentId) return true
        dao.setGroupParent(groupId, parentId, System.currentTimeMillis())
        return syncGroupPaths()
    }

    /** 池换组（不在拖动路径上时走菜单/编辑弹窗）。 */
    suspend fun movePoolToGroup(poolId: String, groupId: String) {
        val dao = appDb.voicePoolDao
        val pool = dao.getAll().firstOrNull { it.id == poolId } ?: return
        if (pool.groupId == groupId) return
        dao.setPoolSlot(poolId, groupId, nextPoolOrder(groupId), System.currentTimeMillis())
        syncGroupPaths()
    }

    /**
     * 拖动结束后的整表回写。两个 slot 列表都由界面按当前可见顺序算好：
     * `id to (parentId/groupId, order)`，order 用全局递增序号 → 同级相对顺序即所见顺序。
     */
    suspend fun saveSlots(
        poolSlots: List<Pair<String, Pair<String, Int>>>,
        groupSlots: List<Pair<String, Pair<String, Int>>>,
    ) {
        appDb.voicePoolDao.saveSlots(poolSlots, groupSlots, System.currentTimeMillis())
        syncGroupPaths()
    }

    /** 用当前分组树刷新所有池的兼容显示字段 `groupName`。 */
    suspend fun syncGroupPaths(): Boolean {
        val dao = appDb.voicePoolDao
        val paths = pathByGroupId()
        val changed = dao.getAll().filter { it.groupName != (paths[it.groupId] ?: "") }
        if (changed.isEmpty()) return true
        dao.updatePools(changed.map { it.copy(groupName = paths[it.groupId].orEmpty()) })
        return true
    }

    private fun validGroupName(name: String): Boolean =
        name.isNotEmpty() && name.length <= MAX_GROUP_NAME && !name.contains(GROUP_SEPARATOR)

    /**
     * 池成员全量替换（成员选择页保存），并保证**一音色一组**（S3③，口径见 D4）。
     *
     * 这次加进来的音色如果本来挂在别的池里，从**别的池**摘掉，不是把它从本池剔除：
     * 用户在这一屏的动作就是"这个音色归这一池"。互斥之后「音色 → 池分类」才是一对一，
     * [voiceClassificationMap] 不必再靠成员表顺序撞第一个有分类的池。
     * 不加唯一索引（给已有表加索引属结构变更，本 fork 不自行提版本号），约束由写入侧守住。
     */
    suspend fun setMembers(poolId: String, voiceIds: Set<String>) {
        val dao = appDb.voicePoolDao
        val before = dao.getAllMembers()
        dao.setMembers(poolId, voiceIds)
        val moved = before.filter { it.voiceId in voiceIds && it.poolId != poolId }
        if (moved.isEmpty()) return
        val poolNames = dao.getAll().associate { it.id to it.name }
        moved.groupBy { it.poolId }.forEach { (otherPoolId, rows) ->
            dao.removeMembers(otherPoolId, rows.map { it.voiceId })
        }
        AppLog.putDebug(
            "一音色一组：${moved.size} 个音色改挂到「${poolNames[poolId].orEmpty()}」，" +
                moved.joinToString("；") {
                    "音色 ${it.voiceId.take(8)} 从「${poolNames[it.poolId].orEmpty()}」移出"
                }
        )
    }

    /**
     * 存量成员表折叠：把"同一个音色挂在多个池里"收敛成一条，幂等，prefs 标记只跑一次。
     *
     * 保留哪一池按可复现的顺序判：①池启用 ②池有结构化分类（这才是折叠真正要保护的东西——
     * 没分类的池留着也不参与匹配）③列表顺序（groupId, order, name）第一个。
     * 摘掉了什么全部写进日志：这一步动的是用户自己组的池，静默收掉等于偷改他的分类。
     */
    suspend fun foldDuplicateMembers() {
        if (prefs().getBoolean(KEY_MEMBERS_FOLDED, false)) return
        ensureSeeded()
        val dao = appDb.voicePoolDao
        val pools = dao.getAll().associateBy { it.id }
        val members = dao.getAllMembers()
        val duplicates = members.groupBy { it.voiceId }.filterValues { it.size > 1 }
        if (duplicates.isNotEmpty()) {
            val dropped = ArrayList<String>()
            duplicates.forEach { (voiceId, rows) ->
                val keep = rows.sortedWith(
                    compareByDescending<VoicePoolMember> { pools[it.poolId]?.enabled == true }
                        .thenByDescending { pools[it.poolId]?.let { pool -> poolClassificationOf(pool) != null } == true }
                        .thenBy { pools[it.poolId]?.groupId.orEmpty() }
                        .thenBy { pools[it.poolId]?.order ?: 0 }
                        .thenBy { pools[it.poolId]?.name.orEmpty() }
                ).first()
                rows.filter { it.poolId != keep.poolId }.forEach { stale ->
                    dao.removeMembers(stale.poolId, listOf(voiceId))
                    dropped += "音色 ${voiceId.take(8)} 移出「${pools[stale.poolId]?.name.orEmpty()}」"
                }
            }
            AppLog.putDebug(
                "声音池收成「一音色一组」：$dropped.size 条重复归属已移出，保留的那一池见下；" +
                    dropped.joinToString("；")
            )
        }
        prefs().edit().putBoolean(KEY_MEMBERS_FOLDED, true).apply()
    }

    suspend fun memberVoiceIds(poolId: String): Set<String> =
        appDb.voicePoolDao.getMembers(poolId).map { it.voiceId }.toSet()

    /** 从池里移除成员；不在池里的忽略。 */
    suspend fun removeMembers(poolId: String, voiceIds: Set<String>): Int {
        if (voiceIds.isEmpty()) return 0
        val existing = memberVoiceIds(poolId)
        val removed = existing intersect voiceIds
        if (removed.isEmpty()) return 0
        setMembers(poolId, existing - removed)
        return removed.size
    }

    /** 音色 id → 所在池的名字列表（列表卡片上的池标签）。 */
    suspend fun poolNamesOfVoices(): Map<String, List<String>> {
        val membership = voicePoolMap()
        if (membership.isEmpty()) return emptyMap()
        val poolNames = listPools().associate { it.id to it.name }
        return membership.mapValues { (_, poolIds) ->
            poolIds.mapNotNull(poolNames::get)
        }.filterValues { it.isNotEmpty() }
    }

    /**
     * 目录音色自动入池，两条规则并行：
     *
     * A. 池分类字段匹配（预置的性别×年龄段矩阵）：池 gender 等于音色性别，且池
     *    ageBand 等于由目录标签推断的年龄段（「少女-清亮情绪」→teen→「女少女」池）；
     *    标签推不出年龄段时落同性别 ageBand=unknown 的池（「失配女」语义恰好对）。
     * B. 标签核心词匹配：自定义池名去掉结尾「音」后的核心词出现在音色标签里。
     *
     * 只处理**尚不属于任何池**的音色——用户已经手动分过池（或清空过归属）的
     * 不碰，避免每次同步覆盖一次手工整理；池不存在不自动建。
     */
    suspend fun autoAssignByTags(): Int {
        val pools = appDb.voicePoolDao.getAll().filter { it.enabled }
        if (pools.isEmpty()) return 0
        val membership = voicePoolMap()
        val pending = appDb.readAloudVoiceDao.getVoices()
            .filter { !it.excluded && membership[it.id].orEmpty().isEmpty() }
        if (pending.isEmpty()) return 0

        data class Target(val poolId: String, val voiceIds: MutableSet<String>)

        val byGender = pools.filter {
            it.gender != VoicePoolEntity.VOICE_POOL_GENDER_UNKNOWN
        }.groupBy { it.gender }
        val joins = mutableListOf<Target>()

        pending.forEach { voice ->
            val traits = ReadAloudVoiceTraits.of(voice.toDomain())
            val tags = traits.tags

            // 「一音色一组」是池的既有语义（入池即移出其它池），自动归类同样每条
            // 音色只定一个目标池：性别矩阵优先，没命中再看自定义池核心词
            val target = run {
                if (traits.gender != ReadAloudVoiceTraits.GENDER_UNKNOWN) {
                    val ageBand = ageBandFromTags(tags)
                    byGender[traits.gender]?.firstOrNull { it.ageBand == ageBand }
                        ?.let { return@run it }
                }
                pools.firstOrNull { pool ->
                    val core = pool.name.trim().trimEnd('音')
                    core.length >= 2 && tags.any { it.contains(core, ignoreCase = true) }
                }
            }
            target?.let { pool ->
                val existing = joins.firstOrNull { it.poolId == pool.id }
                if (existing != null) {
                    existing.voiceIds.add(voice.id)
                } else {
                    joins += Target(pool.id, mutableSetOf(voice.id))
                }
            }
        }
        joins.forEach { addMembers(it.poolId, it.voiceIds) }
        return joins.sumOf { it.voiceIds.size }
    }

    /** 目录标签 → 年龄段；无年龄线索返回 unknown（落「失配男/女」池）。 */
    private fun ageBandFromTags(tags: List<String>): String {
        val text = tags.joinToString(" ").lowercase()
        return when {
            listOf("童", "娃娃", "儿童", "正太", "萝莉").any { it in text } ->
                VoicePoolEntity.VOICE_POOL_AGE_CHILD
            listOf("少女", "少年", "学生", "青涩", "花季").any { it in text } ->
                VoicePoolEntity.VOICE_POOL_AGE_TEEN
            listOf("青年", "轻熟", "御姐", "青女").any { it in text } ->
                VoicePoolEntity.VOICE_POOL_AGE_YOUNG_ADULT
            listOf("中年", "成熟", "大叔", "阿姨", "贵妇").any { it in text } ->
                VoicePoolEntity.VOICE_POOL_AGE_ADULT
            listOf("老年", "奶奶", "爷爷", "苍老").any { it in text } ->
                VoicePoolEntity.VOICE_POOL_AGE_ELDERLY
            else -> VoicePoolEntity.VOICE_POOL_AGE_UNKNOWN
        }
    }

    /**
     * 往池里追加成员，返回真正新增的条数（「朗读引擎与音色」页的添加到声音池）。
     *
     * [setMembers] 是全量替换语义，从引擎页那边过来时手上只有被选中的那几条，
     * 所以先读回现有成员再求并集：池里已有的成员与它们的启用状态一律不动。
     */
    suspend fun addMembers(poolId: String, voiceIds: Set<String>): Int {
        if (voiceIds.isEmpty()) return 0
        val existing = memberVoiceIds(poolId)
        val added = voiceIds - existing
        if (added.isEmpty()) return 0
        setMembers(poolId, existing + added)
        return added.size
    }

    // ---- 多角色规则页专用出口（ViewModel 禁直连 DAO，全部经本 Store） ----

    /** 成员页候选：全部音色 (id → displayName)，用户排除的不在候选里。 */
    suspend fun allVoicePairs(): List<Pair<String, String>> =
        appDb.readAloudVoiceDao.getVoices().filter { !it.excluded }.map { it.id to it.displayName }

    /** 音色 id → 性别/语言/风格/描述标签，成员选择卡展示用。 */
    suspend fun voiceTraits(): Map<String, VoiceTraits> =
        appDb.readAloudVoiceDao.getVoices()
            .associate { it.id to ReadAloudVoiceTraits.of(it.toDomain()) }

    /** 分配表行数据（角色 → 音色/书名/句数）。 */
    data class AssignmentRowData(
        val characterId: String,
        val name: String,
        val poolLabel: String,
        val voiceName: String,
        val bookName: String,
        val lineCount: Int,
    )

    suspend fun assignmentRows(): List<AssignmentRowData> {
        val voices = appDb.readAloudVoiceDao.getVoices().associate { it.id to it.displayName }
        val rows = appDb.castCharacterDao.getAllGlobal()
        val counts = appDb.chapterRoleAssignmentDao.countByCharacter()
            .associate { it.characterId to it.count }
        val books = appDb.bookDao.getAll().associate { it.bookUrl to it.name }
        return rows.map { c ->
            AssignmentRowData(
                characterId = c.id,
                name = c.name,
                poolLabel = c.poolLabel,
                voiceName = voices[c.voiceId].orEmpty(),
                bookName = books[c.bookUrl].orEmpty(),
                lineCount = counts[c.id] ?: 0,
            )
        }
    }

    /**
     * 删配音角色（连同其所有分配行）。
     *
     * 走配音侧那一份删除：它会把镜像的人物档案、音色绑定与本书记忆行一起清掉。
     * 只删这两张表的话，官方人物页还留着一条刚「删掉」的人物，下次进配音页
     * `migrateLegacyProfiles` 又照着那条档案把角色长回来。
     */
    suspend fun deleteCharacter(characterId: String) {
        val character = appDb.castCharacterDao.getById(characterId) ?: return
        BookCastStore.deleteCharacter(character.bookUrl, character.id)
    }
}
