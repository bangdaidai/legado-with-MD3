package io.legado.app.domain.usecase

import io.legado.app.constant.AppLog
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.BookVoiceBinding
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.help.readaloud.ReadAloudVoiceTraits
import io.legado.app.help.readaloud.VoiceTraits
import io.legado.app.help.readaloud.cast.VoiceMatchScoring
import io.legado.app.help.readaloud.cast.VoicePoolStore

/**
 * 给本章真正有台词、却还没有音色的角色自动挑一个音色，落成 [BookVoiceBinding.SOURCE_AUTO] 绑定。
 *
 * 绑定策略：
 * - 用户手动绑的、导入的、或 locked 的绑定永不覆盖；
 * - 自动绑定只在音色失效（被删、停用、不可用）时重挑，平时不重复评估；
 * - **候选域与池侧同一份**：角色卡指了声音池就只在池内启用音色里挑（池本身就是性别分组，
 *   不再按性别筛），与 [io.legado.app.help.readaloud.cast.CastVoicePicker.ensureVoice] 同域、
 *   同一份打分——同一个角色无论先被哪条链碰到，拿到的都是同一个声音。池内没有启用音色
 *   时不从池外捞（否则配音页显示池内无音色、耳朵里是池外的声音）；
 * - 没指池的角色只有能判断性别时才分配，判断不出来就交给原来的性别兜底链，不瞎猜；
 * - 没池时同性别里先按**结构化年龄段**精确匹配，再按角色卡的性格关键词与音色风格标签的重合度
 *   兜底排序。结构化匹配优先：年龄与性别来自用户标注或声音池的分类（见
 *   [io.legado.app.help.readaloud.cast.VoiceLabelStore.classificationMap]），
 *   比拿关键词撞风格标签可靠得多。
 * - **排序本身只有一份实现**：[VoiceMatchScoring]。
 */
class AutoAssignVoiceBindingsUseCase(
    private val voiceGateway: ReadAloudVoiceGateway,
) {

    suspend operator fun invoke(
        bookUrl: String,
        performances: List<CharacterPerformanceProfile>,
        /**
         * voiceId → (性别, 年龄段)。音色行自己既没有年龄列也没有性别列，
         * 这一份由「用户标注 > 声音池分类」派生；缺失的音色退回引擎声明 / 名字启发。
         *
         * 以前这条链只取年龄段、把性别丢掉（池分类里那一半没人读），S3② 修的就是这里。
         */
        voiceClassification: Map<String, Pair<String, String>> = emptyMap(),
        now: Long = System.currentTimeMillis(),
        /** 池名 → 池内启用音色 id；默认直连声音池，测试注入假实现。 */
        poolVoiceIdsOf: suspend (String) -> List<String> = { label ->
            runCatching { VoicePoolStore.enabledVoiceIdsOfPool(label) }.getOrDefault(emptyList())
        },
    ): List<BookVoiceBinding> {
        if (bookUrl.isEmpty() || performances.isEmpty()) return emptyList()
        val voices = voiceGateway.getEnabledVoices().filter { it.available }
        if (voices.isEmpty()) return emptyList()
        val bindings = voiceGateway.getBindings(bookUrl)
        val usableVoiceIds = voices.mapTo(hashSetOf(), ReadAloudVoice::id)
        val bindingsBySubject = bindings.associateBy { it.subjectType to it.subjectId }
        // 已被其它主体占用的音色尽量不重复分配，免得两个角色一个声音
        val takenVoiceIds = bindings.filter { it.voiceId in usableVoiceIds }
            .mapTo(hashSetOf(), BookVoiceBinding::voiceId)
        val preferredEngine = preferredEngineType(bindings, voices)
        val traitsById = voices.associate { it.id to ReadAloudVoiceTraits.of(it) }
        val voicesByGender = voices.groupBy { genderOf(it, traitsById, voiceClassification) }
        // 同池角色共享一次查询：池名各查一遍，命中过的不再打第二遍
        val poolVoiceIdsByLabel = HashMap<String, List<String>>()

        val created = mutableListOf<Pair<BookVoiceBinding, String>>()
        // 跳过留痕：性别推不出、池内没有启用音色、没有同性别候选的角色不分配本身是设计
        // 行为（交给性别兜底链），但过去静默跳过，分镜页只看得到「已分配 0/N 段」却查不出原因
        var skippedUnknownGender = 0
        var skippedEmptyPool = 0
        var skippedNoCandidate = 0
        performances.forEach { performance ->
            val subject = BookVoiceBinding.SUBJECT_CHARACTER to performance.characterId
            val existing = bindingsBySubject[subject]
            if (!existing.needsAutoAssignment(usableVoiceIds)) return@forEach
            val poolVoiceIds = performance.voicePoolLabel.takeIf { it.isNotBlank() }
                ?.let { label -> poolVoiceIdsByLabel.getOrPut(label) { poolVoiceIdsOf(label) } }
            val candidates: List<ReadAloudVoice>
            // 池模式下没有性别推断这一步（池本身就是明确指定的分组），只留给 confidence 用
            val explicitGender: String?
            when {
                poolVoiceIds == null -> {
                    val gender = performance.gender() ?: run {
                        skippedUnknownGender++
                        return@forEach
                    }
                    candidates = voicesByGender[gender].orEmpty()
                    explicitGender = gender
                }
                poolVoiceIds.isEmpty() -> {
                    skippedEmptyPool++
                    return@forEach
                }
                else -> {
                    candidates = voices.filter { it.id in poolVoiceIds }
                    explicitGender = null
                }
            }
            val voice = pick(
                candidates = candidates,
                traitsById = traitsById,
                voiceClassification = voiceClassification,
                taken = takenVoiceIds,
                preferredEngine = preferredEngine,
                performance = performance,
            ) ?: run {
                skippedNoCandidate++
                return@forEach
            }
            takenVoiceIds += voice.id
            val binding = BookVoiceBinding(
                bookUrl = bookUrl,
                subjectType = BookVoiceBinding.SUBJECT_CHARACTER,
                subjectId = performance.characterId,
                voiceId = voice.id,
                locked = false,
                source = BookVoiceBinding.SOURCE_AUTO,
                confidence = when {
                    explicitGender == null -> CONFIDENCE_EXPLICIT
                    performance.voiceGender == explicitGender -> CONFIDENCE_EXPLICIT
                    else -> CONFIDENCE_INFERRED
                },
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            voiceGateway.upsertBinding(binding)
            created += binding to voice.displayName
        }
        if (created.isNotEmpty()) {
            AppLog.putDebug(
                "自动分配音色 ${created.size} 个角色：" +
                    created.joinToString { (binding, name) -> "$name(${binding.confidence})" }
            )
        }
        if (skippedUnknownGender > 0 || skippedEmptyPool > 0 || skippedNoCandidate > 0) {
            val reasons = buildList {
                if (skippedUnknownGender > 0) add("$skippedUnknownGender 个角色性别未知")
                if (skippedEmptyPool > 0) add("$skippedEmptyPool 个角色的池内没有启用音色")
                if (skippedNoCandidate > 0) add("$skippedNoCandidate 个角色无可用候选音色")
            }
            AppLog.put("自动分配音色跳过：${reasons.joinToString("、")}，这些角色按性别兜底链朗读")
        }
        return created.map { it.first }
    }

    private fun BookVoiceBinding?.needsAutoAssignment(usableVoiceIds: Set<String>): Boolean {
        this ?: return true
        if (locked || source != BookVoiceBinding.SOURCE_AUTO) return false
        return voiceId !in usableVoiceIds
    }

    /** 没指池时的性别判定：角色卡写的性别优先，其次男主/女主这类定位（指了池的角色不走这里） */
    private fun CharacterPerformanceProfile.gender(): String? = when (voiceGender) {
        ReadAloudVoiceTraits.GENDER_MALE,
        ReadAloudVoiceTraits.GENDER_FEMALE -> voiceGender

        else -> when (role) {
            BookVoiceBinding.SUBJECT_MALE_LEAD,
            BookVoiceBinding.SUBJECT_MALE_SUPPORTING -> ReadAloudVoiceTraits.GENDER_MALE

            BookVoiceBinding.SUBJECT_FEMALE_LEAD,
            BookVoiceBinding.SUBJECT_FEMALE_SUPPORTING -> ReadAloudVoiceTraits.GENDER_FEMALE

            else -> null
        }
    }

    /**
     * 同性别候选里取最好的一条。
     *
     * 打分与排序全在 [VoiceMatchScoring.order]（占用 → 年龄段 → 关键词 → 引擎 → 名字 → id），
     * 本方法只负责把手里的音色行折算成候选。全序、无随机：同书同池重跑给出同一个音色。
     */
    private fun pick(
        candidates: List<ReadAloudVoice>,
        traitsById: Map<String, VoiceTraits>,
        voiceClassification: Map<String, Pair<String, String>>,
        taken: Set<String>,
        preferredEngine: String,
        performance: CharacterPerformanceProfile,
    ): ReadAloudVoice? {
        if (candidates.isEmpty()) return null
        val best = VoiceMatchScoring.best(
            candidates = candidates.map { voice ->
                VoiceMatchScoring.candidateOf(
                    voice = voice,
                    traits = traitsById[voice.id],
                    ageBand = voiceClassification[voice.id]?.second.orEmpty(),
                    // 占用在这里只有"有没有人用过"两档；池那一侧才按真实次数摊开
                    usage = if (voice.id in taken) 1 else 0,
                )
            },
            characterAgeBand = performance.voiceAgeBand,
            keywords = VoiceMatchScoring.keywords(performance.voiceAgeBand, performance.personality),
            preferredEngine = preferredEngine,
        ) ?: return null
        return candidates.firstOrNull { it.id == best.id }
    }

    /**
     * 这条链上音色的性别：标注 / 池分类里的明确取值优先，两者都没有才看引擎声明与名字启发。
     *
     * 分类里的 `unknown` 与空串都算"没说过"，不能让一个还没分类的池把名字里明明写着
     * 「晓（女）」的音色判成无性别。
     */
    private fun genderOf(
        voice: ReadAloudVoice,
        traitsById: Map<String, VoiceTraits>,
        voiceClassification: Map<String, Pair<String, String>>,
    ): String {
        val classified = voiceClassification[voice.id]?.first?.trim()
        return when (classified) {
            ReadAloudVoiceTraits.GENDER_MALE, ReadAloudVoiceTraits.GENDER_FEMALE -> classified
            else -> traitsById.getValue(voice.id).gender
        }
    }

    /**
     * 结构化年龄段匹配分：精确 100 / 相邻 50 / 任一 unknown 0（退化到关键词） / 不匹配 -10。
     *
     * 实现已并入 [VoiceMatchScoring]（两处选音共用一份），这里留一扇门给既有单测，
     * 也让调用方不必为了问一个分数而构造候选列表。
     */
    internal fun ageBandMatchScore(voiceBand: String?, characterBand: String): Int =
        VoiceMatchScoring.ageBandMatchScore(voiceBand, characterBand)

    /** 跟随这本书已有绑定的引擎，一本书里混引擎会让协调器来回切换 */
    private fun preferredEngineType(
        bindings: List<BookVoiceBinding>,
        voices: List<ReadAloudVoice>,
    ): String {
        val voicesById = voices.associateBy(ReadAloudVoice::id)
        return bindings.mapNotNull { voicesById[it.voiceId]?.engineType }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: voices.groupingBy(ReadAloudVoice::engineType).eachCount()
                .maxByOrNull { it.value }?.key
            ?: ReadAloudVoice.ENGINE_SYSTEM
    }
}

/** 性别是角色卡里明确写的 */
private const val CONFIDENCE_EXPLICIT = 0.85f

/** 性别是从男主/女主这类定位推出来的 */
private const val CONFIDENCE_INFERRED = 0.7f
