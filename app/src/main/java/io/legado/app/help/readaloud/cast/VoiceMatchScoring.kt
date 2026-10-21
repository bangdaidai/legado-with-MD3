package io.legado.app.help.readaloud.cast

import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.help.readaloud.VoiceTraits

/**
 * 自动选音的唯一打分链。
 *
 * 这条链原来有两份各说各话的实现：`AutoAssignVoiceBindingsUseCase`（朗读链路给没绑音色的角色
 * 建绑定）按「结构化年龄段 → 风格关键词 → 引擎 → 名字」排，
 * [CastVoicePicker]（给分配表里没音色的角色补一个）按「本书用得最少 → **随机**」排。
 * 同一个角色走哪条、拿到哪个声音，取决于它先被哪条链碰到，重跑一次还会换一个——
 * 这就是 S4 要收的那处「每个问题两处答案」。
 *
 * 现在两处都折算成 [VoiceCandidate] 交给同一个 [order]：**占用少 → 年龄段匹配 → 风格关键词
 * → 引擎 → 名字 → id**。全序、无随机，所以同一本书同一个池重跑给出的是同一个音色；
 * 「不要永远落在池子第一格」由占用数承担（第一个角色占走 A，第二个就看到 A 用过了）。
 */
object VoiceMatchScoring {

    /**
     * 一条候选音色。
     *
     * 两处选音手里的东西不一样（一处是领域音色行，一处是池里的音色 id），
     * 所以先把它们折算成同一形状再排序，比较器只认这个形状。
     */
    data class VoiceCandidate(
        val id: String,
        val displayName: String = "",
        val engineType: String = "",
        /** 音色的性别/风格标签（引擎声明 + 名字启发）；查不到行时可以为 null。 */
        val traits: VoiceTraits? = null,
        /** 音色的结构化年龄段：用户标注 > 所属声音池，见 [VoiceLabelStore.classificationMap]。 */
        val ageBand: String = "",
        /** 本书已经有多少角色在用它。0 = 没人用过，优先给新角色。 */
        val usage: Int = 0,
    )

    /**
     * 全序比较器。
     *
     * [characterAgeBand] 是角色档案/所在池给出的年龄段，[keywords] 是角色的年龄词 + 性格词。
     * 两者拿不到时传空串/空表：年龄项全 0、关键词项全 0，序自动退回「占用 → 引擎 → 名字 → id」，
     * 与引入结构化匹配之前的行为一致。
     */
    fun order(
        characterAgeBand: String,
        keywords: List<String>,
        preferredEngine: String,
    ): Comparator<VoiceCandidate> =
        compareBy<VoiceCandidate> { it.usage }
            .thenByDescending { ageBandMatchScore(it.ageBand, characterAgeBand) }
            .thenByDescending { traitScore(it.traits, keywords) }
            .thenByDescending { it.engineType == preferredEngine }
            .thenBy { it.displayName }
            .thenBy { it.id }

    /** 按 [order] 取最好的一条；候选为空返回 null。 */
    fun best(
        candidates: List<VoiceCandidate>,
        characterAgeBand: String,
        keywords: List<String>,
        preferredEngine: String,
    ): VoiceCandidate? =
        if (candidates.isEmpty()) null else candidates.minWith(order(characterAgeBand, keywords, preferredEngine))

    /**
     * 结构化年龄段匹配分：精确 100 / 相邻 50 / 任一 unknown 0（退化到关键词）/ 不匹配 -10。
     *
     * 音色那一侧来自用户标注或所属声音池的分类，角色那一侧来自档案。
     * 两边都是 unknown 时返回 0——此时完全交给关键词排序，保持引入结构化匹配前的行为。
     */
    fun ageBandMatchScore(voiceBand: String?, characterBand: String): Int {
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

    /**
     * 角色卡的年龄段与性格拆成关键词，用来跟音色声明的风格标签对齐。
     *
     * 性格是自由文本，按常见分隔符切开；单字太容易误命中，所以丢掉。
     */
    fun keywords(voiceAgeBand: String, personality: String): List<String> {
        val ageWords = when (voiceAgeBand) {
            BookCharacterProfile.VOICE_AGE_CHILD -> CHILD_HINTS
            BookCharacterProfile.VOICE_AGE_TEEN -> TEEN_HINTS
            BookCharacterProfile.VOICE_AGE_YOUNG_ADULT -> YOUNG_ADULT_HINTS
            BookCharacterProfile.VOICE_AGE_ADULT -> ADULT_HINTS
            BookCharacterProfile.VOICE_AGE_ELDERLY -> ELDERLY_HINTS
            else -> emptyList()
        }
        val personalityWords = personality.split(PERSONALITY_DELIMITERS)
            .map(String::trim).filter { it.length >= 2 }
        return ageWords + personalityWords
    }

    /** 命中一个标签算一分，没有标签或没有关键词就都是 0 分，排序退回引擎/名字。 */
    fun traitScore(traits: VoiceTraits?, keywords: List<String>): Int {
        if (traits == null || keywords.isEmpty()) return 0
        val text = traits.descriptors.joinToString(" ")
        if (text.isEmpty()) return 0
        return keywords.count { it in text }
    }

    /** 音色行 → 候选（名字、引擎、标签取音色自己那一份，年龄与占用由调用方补）。 */
    fun candidateOf(
        voice: ReadAloudVoice,
        traits: VoiceTraits?,
        ageBand: String,
        usage: Int,
    ): VoiceCandidate = VoiceCandidate(
        id = voice.id,
        displayName = voice.displayName,
        engineType = voice.engineType,
        traits = traits,
        ageBand = ageBand,
        usage = usage,
    )
}

/** 结构化年龄段精确匹配（音色分类 == 角色年龄段） */
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
