package io.legado.app.help.readaloud.cast

import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.help.readaloud.VoiceTraits
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自动选音那条唯一的打分链（S4）。
 *
 * 这里盯的是三件事：**结果全序可复现**（没有随机，重跑同一个池给同一个音色）、
 * **占用少的先上**（连续进池的角色在整池用遍之前不撞声音，这一条以前靠 random 保证）、
 * **结构化年龄段压过名字与引擎**（用户标注或声音池给的年龄，比音色叫什么更重要）。
 *
 * 原来这份测试测的是 `CastVoicePicker.leastUsedVoices` —— 那个函数连同「用得最少的一批里
 * 随机取一个」一起并进了本文件测的这条链；朗读侧的
 * [io.legado.app.domain.usecase.AutoAssignVoiceBindingsUseCase] 现在也走同一个比较器。
 */
class VoiceMatchScoringTest {

    private val child = BookCharacterProfile.VOICE_AGE_CHILD
    private val teen = BookCharacterProfile.VOICE_AGE_TEEN
    private val youngAdult = BookCharacterProfile.VOICE_AGE_YOUNG_ADULT
    private val adult = BookCharacterProfile.VOICE_AGE_ADULT
    private val elderly = BookCharacterProfile.VOICE_AGE_ELDERLY

    private fun candidate(
        id: String,
        name: String = id,
        engineType: String = "sys",
        ageBand: String = "",
        descriptors: List<String> = emptyList(),
        usage: Int = 0,
    ) = VoiceMatchScoring.VoiceCandidate(
        id = id,
        displayName = name,
        engineType = engineType,
        traits = VoiceTraits(tags = descriptors),
        ageBand = ageBand,
        usage = usage,
    )

    /** 一个「青年」池：两条精确、一条差一档；名字给出确定的先后。 */
    private val pool = listOf(
        candidate("v1", name = "Anna", ageBand = youngAdult),
        candidate("v2", name = "Bea", ageBand = youngAdult),
        candidate("v3", name = "Cai", ageBand = adult),
    )

    private fun best(
        candidates: List<VoiceMatchScoring.VoiceCandidate>,
        ageBand: String = youngAdult,
        keywords: List<String> = emptyList(),
        engine: String = "",
    ) = VoiceMatchScoring.best(candidates, ageBand, keywords, engine)?.id

    /** 同一个池重跑五十次都是同一个答案：这就是把 random 换成全序后要守住的那一条。 */
    @Test
    fun `the same pool answers the same way every run`() {
        repeat(50) { assertEquals("v1", best(pool)) }
    }

    /**
     * 整池用遍之前不撞声音：占用数排在打分链第一位。
     * 三个角色依次进同一个空池，拿到的是三把不同的声音。
     */
    @Test
    fun `an untouched voice always wins over one the book already uses`() {
        val usage = mutableMapOf<String, Int>()
        val picks = (1..3).map {
            val picked = best(pool.map { it.copy(usage = usage[it.id] ?: 0) }) ?: error("空候选")
            usage[picked] = (usage[picked] ?: 0) + 1
            picked
        }
        assertEquals(listOf("v1", "v2", "v3"), picks)
    }

    /** 整池都被占过一次之后按年龄分继续分高下，而不是退回随机。 */
    @Test
    fun `once every voice is used the age band decides`() {
        val used = pool.map { it.copy(usage = 1) }
        assertEquals("v3", best(used, ageBand = elderly))
    }

    /** 年龄压过名字：叫得更好听（排得更前）但明显错配的音色要挨罚。 */
    @Test
    fun `a matching age band outranks the name`() {
        assertEquals(
            "b",
            best(
                listOf(
                    candidate("a", name = "Aaa", ageBand = elderly),
                    candidate("b", name = "Bee", ageBand = child),
                ),
                ageBand = child,
            ),
        )
    }

    /** 相邻年龄段算部分匹配（50），跨两档算不匹配（-10）。 */
    @Test
    fun `adjacent age bands score above distant ones`() {
        assertEquals(
            "near",
            best(
                listOf(
                    candidate("near", ageBand = adult),
                    candidate("far", ageBand = child),
                ),
                ageBand = youngAdult,
            ),
        )
    }

    /** 音色侧没年龄段（既没标注也不在分类池里）时退回风格关键词，年龄档给 unknown 同理。 */
    @Test
    fun `keywords take over when the age band is missing`() {
        val candidates = listOf(
            candidate("plain", name = "Zed", descriptors = listOf("沉稳")),
            candidate("teen", name = "Amy", descriptors = listOf("少年")),
        )
        assertEquals(
            "teen",
            best(candidates, ageBand = "", keywords = VoiceMatchScoring.keywords(teen, "开朗")),
        )
        assertEquals(
            "teen",
            best(
                candidates.map { it.copy(ageBand = BookCharacterProfile.VOICE_AGE_UNKNOWN) },
                ageBand = BookCharacterProfile.VOICE_AGE_UNKNOWN,
                keywords = VoiceMatchScoring.keywords(teen, "开朗"),
            ),
        )
    }

    /** 年龄与关键词都分不出高下时按偏好引擎、再按名字收尾，仍然是全序。 */
    @Test
    fun `engine then name break the remaining ties`() {
        val candidates = listOf(
            candidate("sysB", name = "Bo", engineType = "sys"),
            candidate("httpA", name = "Ann", engineType = "http"),
            candidate("httpB", name = "Cy", engineType = "http"),
        )
        assertEquals("httpA", best(candidates, ageBand = "", engine = "http"))
        // 没有引擎偏好：名字最靠前的那条胜出，与调用次数无关
        assertEquals("httpA", best(candidates, ageBand = ""))
    }

    /** 空候选返回 null，调用方据此原样返回角色，不瞎填一个。 */
    @Test
    fun `no candidates means no pick`() {
        assertEquals(null, VoiceMatchScoring.best(emptyList(), "", emptyList(), ""))
    }

    /** 性格是自由文本：按常见分隔符切开、丢掉单字，年龄词拼在前面。 */
    @Test
    fun `personality text splits into keywords`() {
        assertEquals(
            listOf("少年", "少女", "青少年", "teen", "young", "开朗", "有点毒舌"),
            VoiceMatchScoring.keywords(teen, "开朗、有点毒舌，x"),
        )
    }

    /** 打分链只有一份：年龄段那四档分数与朗读侧单测盯的是同一个实现。 */
    @Test
    fun `age band scores are the shared ones`() {
        assertEquals(100, VoiceMatchScoring.ageBandMatchScore(youngAdult, youngAdult))
        assertEquals(50, VoiceMatchScoring.ageBandMatchScore(adult, youngAdult))
        assertEquals(-10, VoiceMatchScoring.ageBandMatchScore(child, elderly))
        assertEquals(0, VoiceMatchScoring.ageBandMatchScore("", youngAdult))
        assertEquals(
            0,
            VoiceMatchScoring.ageBandMatchScore(
                BookCharacterProfile.VOICE_AGE_UNKNOWN,
                youngAdult,
            ),
        )
    }
}
