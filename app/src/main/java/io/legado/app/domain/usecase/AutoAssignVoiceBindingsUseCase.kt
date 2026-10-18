package io.legado.app.domain.usecase

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.BookVoiceBinding
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.help.readaloud.ReadAloudVoiceTraits
import io.legado.app.help.readaloud.VoiceTraits

/**
 * 给本章真正有台词、却还没有音色的角色自动挑一个音色，落成 [BookVoiceBinding.SOURCE_AUTO] 绑定。
 *
 * 绑定策略：
 * - 用户手动绑的、导入的、或 locked 的绑定永不覆盖；
 * - 自动绑定只在音色失效（被删、停用、不可用）时重挑，平时不重复评估；
 * - 只有能判断角色性别时才分配，判断不出来就交给原来的性别兜底链，不瞎猜；
 * - 同性别里先按**结构化年龄段**（音色所属声音池派生）精确匹配，再按角色卡的性格关键词
 *   与音色风格标签的重合度兜底排序。结构化匹配优先：音色的年龄来自池成员关系，
 *   池是「性别 × 年龄」的分类学，比拿关键词撞风格标签可靠得多。
 */
class AutoAssignVoiceBindingsUseCase(
    private val voiceGateway: ReadAloudVoiceGateway,
) {

    suspend operator fun invoke(
        bookUrl: String,
        performances: List<CharacterPerformanceProfile>,
        /** voiceId → 结构化年龄段（从声音池派生，见 VoicePoolStore.voiceClassificationMap）。 */
        voiceAgeBands: Map<String, String> = emptyMap(),
        now: Long = System.currentTimeMillis(),
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
        val voicesByGender = voices.groupBy { traitsById.getValue(it.id).gender }

        val created = mutableListOf<Pair<BookVoiceBinding, String>>()
        performances.forEach { performance ->
            val subject = BookVoiceBinding.SUBJECT_CHARACTER to performance.characterId
            val existing = bindingsBySubject[subject]
            if (!existing.needsAutoAssignment(usableVoiceIds)) return@forEach
            val gender = performance.gender() ?: return@forEach
            val voice = pick(
                candidates = voicesByGender[gender].orEmpty(),
                traitsById = traitsById,
                voiceAgeBands = voiceAgeBands,
                taken = takenVoiceIds,
                preferredEngine = preferredEngine,
                performance = performance,
            ) ?: return@forEach
            takenVoiceIds += voice.id
            val binding = BookVoiceBinding(
                bookUrl = bookUrl,
                subjectType = BookVoiceBinding.SUBJECT_CHARACTER,
                subjectId = performance.characterId,
                voiceId = voice.id,
                locked = false,
                source = BookVoiceBinding.SOURCE_AUTO,
                confidence = if (performance.voiceGender == gender) {
                    CONFIDENCE_EXPLICIT
                } else {
                    CONFIDENCE_INFERRED
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
        return created.map { it.first }
    }

    private fun BookVoiceBinding?.needsAutoAssignment(usableVoiceIds: Set<String>): Boolean {
        this ?: return true
        if (locked || source != BookVoiceBinding.SOURCE_AUTO) return false
        return voiceId !in usableVoiceIds
    }

    /** 只有性别明确才自动分配：角色卡写的性别优先，其次男主/女主这类定位 */
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
     * 候选先按结构化年龄段匹配分排，再按风格标签命中数，再按引擎/名字。
     * 音色的性别与标签统一由 [ReadAloudVoiceTraits] 解析；年龄段来自声音池成员关系（[voiceAgeBands]）。
     */
    private fun pick(
        candidates: List<ReadAloudVoice>,
        traitsById: Map<String, VoiceTraits>,
        voiceAgeBands: Map<String, String>,
        taken: Set<String>,
        preferredEngine: String,
        performance: CharacterPerformanceProfile,
    ): ReadAloudVoice? {
        if (candidates.isEmpty()) return null
        // 同性别音色全被占完了就允许复用，几个角色共用总比整本掉回兜底音好
        val pool = candidates.filterNot { it.id in taken }.ifEmpty { candidates }
        val keywords = performance.matchKeywords()
        return pool.minWithOrNull(
            compareByDescending<ReadAloudVoice> {
                ageBandMatchScore(voiceAgeBands[it.id], performance.voiceAgeBand)
            }
                .thenByDescending { traitsById[it.id].score(keywords) }
                .thenByDescending { it.engineType == preferredEngine }
                .thenBy(ReadAloudVoice::displayName)
                .thenBy(ReadAloudVoice::id)
        )
    }

    /**
     * 结构化年龄段匹配分：精确 100 / 相邻 50 / 任一 unknown 0（退化到关键词） / 不匹配 -10。
     *
     * 音色的年龄段来自所属声音池的分类，角色的来自档案 [CharacterPerformanceProfile.voiceAgeBand]。
     * 两边都是 unknown 时返回 0——此时完全交给关键词排序，保持引入结构化匹配前的行为。
     */
    internal fun ageBandMatchScore(voiceBand: String?, characterBand: String): Int {
        val v = voiceBand?.trim().orEmpty()
        val c = characterBand.trim()
        val voiceKnown = v.isNotEmpty() && v != BookCharacterProfile.VOICE_AGE_UNKNOWN
        val charKnown = c.isNotEmpty() && c != BookCharacterProfile.VOICE_AGE_UNKNOWN
        if (!voiceKnown || !charKnown) return 0
        if (v == c) return SCORE_AGE_EXACT
        return if (isAdjacentAgeBand(v, c)) SCORE_AGE_ADJACENT else SCORE_AGE_MISMATCH
    }

    /** 相邻年龄段（child↔teen↔young_adult↔adult↔elderly）算部分匹配，跨两档算不匹配。 */
    private fun isAdjacentAgeBand(a: String, b: String): Boolean {
        val order = BookCharacterProfile.ALL_VOICE_AGE_BANDS
            .filter { it != BookCharacterProfile.VOICE_AGE_UNKNOWN }
        val ia = order.indexOf(a)
        val ib = order.indexOf(b)
        if (ia < 0 || ib < 0) return false
        return kotlin.math.abs(ia - ib) == 1
    }

    /** 角色卡的年龄段与性格拆成关键词，用来跟音色声明的风格标签对齐 */
    private fun CharacterPerformanceProfile.matchKeywords(): List<String> {
        val ageWords = when (voiceAgeBand) {
            BookCharacterProfile.VOICE_AGE_CHILD -> CHILD_HINTS
            BookCharacterProfile.VOICE_AGE_TEEN -> TEEN_HINTS
            BookCharacterProfile.VOICE_AGE_YOUNG_ADULT -> YOUNG_ADULT_HINTS
            BookCharacterProfile.VOICE_AGE_ADULT -> ADULT_HINTS
            BookCharacterProfile.VOICE_AGE_ELDERLY -> ELDERLY_HINTS
            else -> emptyList()
        }
        // 性格是自由文本，按常见分隔符切开，单字太容易误命中所以丢掉
        val personalityWords = personality.split(PERSONALITY_DELIMITERS)
            .map(String::trim).filter { it.length >= 2 }
        return ageWords + personalityWords
    }

    /** 命中一个标签算一分，没有标签或没有关键词就都是 0 分，排序退回原来的引擎优先规则 */
    private fun VoiceTraits?.score(keywords: List<String>): Int {
        if (this == null || keywords.isEmpty()) return 0
        val text = descriptors.joinToString(" ")
        if (text.isEmpty()) return 0
        return keywords.count { it in text }
    }

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

/** 结构化年龄段精确匹配（音色池分类 == 角色档案年龄段） */
private const val SCORE_AGE_EXACT = 100

/** 结构化年龄段相邻（如 young_adult ↔ adult） */
private const val SCORE_AGE_ADJACENT = 50

/** 结构化年龄段不匹配（跨两档以上，如 child ↔ elderly）——主动惩罚，避免明显错配 */
private const val SCORE_AGE_MISMATCH = -10

private val PERSONALITY_DELIMITERS = Regex("[,，、;；/|\\s]+")

// 角色卡年龄段 -> 音色风格标签里常见的说法, 命中就往前排
private val CHILD_HINTS = listOf("童", "儿童", "小孩", "child", "kid")
private val TEEN_HINTS = listOf("少年", "少女", "青少年", "teen", "young")
private val YOUNG_ADULT_HINTS = listOf("青年", "young adult", "youth")
private val ADULT_HINTS = listOf("成年", "成熟", "adult", "mature")
private val ELDERLY_HINTS = listOf("老年", "苍老", "老人", "elder", "old")
