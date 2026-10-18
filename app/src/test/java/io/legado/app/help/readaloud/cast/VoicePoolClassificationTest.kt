package io.legado.app.help.readaloud.cast

import io.legado.app.data.entities.VoicePoolEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 声音池结构化分类（性别 × 年龄段）的判定逻辑。
 *
 * 池是「角色年龄性别 → 音色」精确匹配链路的唯一桥梁：音色本身没有年龄字段，
 * 年龄从池成员关系派生，而池的性别/年龄记在 [VoicePoolEntity.gender] / [VoicePoolEntity.ageBand]。
 * 判定语义：gender/ageBand 至少有一个非 unknown 才算"有分类"，否则返回 null（调用方按 unknown 兜底）。
 */
class VoicePoolClassificationTest {

    @Test
    fun `fully classified pool returns both fields`() {
        val pool = VoicePoolEntity(
            id = "p1",
            name = "女青年",
            gender = VoicePoolEntity.VOICE_POOL_GENDER_FEMALE,
            ageBand = VoicePoolEntity.VOICE_POOL_AGE_YOUNG_ADULT,
        )
        assertEquals(
            VoicePoolEntity.VOICE_POOL_GENDER_FEMALE to VoicePoolEntity.VOICE_POOL_AGE_YOUNG_ADULT,
            VoicePoolStore.poolClassificationOf(pool),
        )
    }

    @Test
    fun `pool with only gender known still counts as classified`() {
        val pool = VoicePoolEntity(
            id = "p2",
            name = "失配男",
            gender = VoicePoolEntity.VOICE_POOL_GENDER_MALE,
            ageBand = VoicePoolEntity.VOICE_POOL_AGE_UNKNOWN,
        )
        assertEquals(
            VoicePoolEntity.VOICE_POOL_GENDER_MALE to VoicePoolEntity.VOICE_POOL_AGE_UNKNOWN,
            VoicePoolStore.poolClassificationOf(pool),
        )
    }

    @Test
    fun `pool with only ageBand known still counts as classified`() {
        val pool = VoicePoolEntity(
            id = "p3",
            name = "自建池",
            gender = VoicePoolEntity.VOICE_POOL_GENDER_UNKNOWN,
            ageBand = VoicePoolEntity.VOICE_POOL_AGE_TEEN,
        )
        assertEquals(
            VoicePoolEntity.VOICE_POOL_GENDER_UNKNOWN to VoicePoolEntity.VOICE_POOL_AGE_TEEN,
            VoicePoolStore.poolClassificationOf(pool),
        )
    }

    @Test
    fun `fully unknown pool returns null so caller falls back to keyword matching`() {
        val pool = VoicePoolEntity(
            id = "p4",
            name = "卡通人物",
            gender = VoicePoolEntity.VOICE_POOL_GENDER_UNKNOWN,
            ageBand = VoicePoolEntity.VOICE_POOL_AGE_UNKNOWN,
        )
        assertNull(VoicePoolStore.poolClassificationOf(pool))
    }

    @Test
    fun `blank gender and ageBand treated as unknown`() {
        val pool = VoicePoolEntity(
            id = "p5",
            name = "空白池",
            gender = "",
            ageBand = "",
        )
        assertNull(VoicePoolStore.poolClassificationOf(pool))
    }
}
