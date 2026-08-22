package io.legado.app.help.readaloud.resolve

import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.SpeakerCharacter
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalCharacterSpeakerResolverTest {

    @Test
    fun `resolves explicit speaker before quote`() {
        val paragraph = paragraph("张三说：“你好！”")
        val result = resolve(
            paragraph = paragraph,
            segment = dialogue(paragraph, "“你好！”"),
            characters = listOf(character("zhang", "张三")),
        )

        assertEquals("zhang", result.characterId)
        assertEquals("张三", result.characterName)
        assertEquals(SpeechResolutionSource.Local, result.source)
    }

    @Test
    fun `resolves alias after quote`() {
        val paragraph = paragraph("“别动！”老李喝道。")
        val result = resolve(
            paragraph = paragraph,
            segment = dialogue(paragraph, "“别动！”"),
            characters = listOf(character("li", "李四", listOf("老李"))),
        )

        assertEquals("li", result.characterId)
    }

    @Test
    fun `keeps ambiguous alias unresolved`() {
        val paragraph = paragraph("阿青说：“走吧！”")
        val result = resolve(
            paragraph = paragraph,
            segment = dialogue(paragraph, "“走吧！”"),
            characters = listOf(
                character("a", "张青", listOf("阿青")),
                character("b", "李青", listOf("阿青")),
            ),
        )

        assertNull(result.characterId)
    }

    @Test
    fun `does not match alias embedded in longer name`() {
        val paragraph = paragraph("王小明说：“你好！”")
        val result = resolve(
            paragraph = paragraph,
            segment = dialogue(paragraph, "“你好！”"),
            characters = listOf(character("ming", "小明")),
        )

        assertNull(result.characterId)
    }

    @Test
    fun `resolves speaker with long modifier before verb`() {
        val paragraph = paragraph("宝珠眼睛亮晶晶的，认真说：“上路时我身上入不敷出。”")
        val result = resolve(
            paragraph = paragraph,
            segment = dialogue(paragraph, "“上路时我身上入不敷出。”"),
            characters = listOf(character("baozhu", "宝珠")),
        )

        assertEquals("baozhu", result.characterId)
    }

    @Test
    fun `prefers the name closest to the speech verb`() {
        val paragraph = paragraph("陈师古买下了你，宝珠轻轻地说：“我把你赎回来。”")
        val result = resolve(
            paragraph = paragraph,
            segment = dialogue(paragraph, "“我把你赎回来。”"),
            characters = listOf(
                character("chen", "陈师古"),
                character("baozhu", "宝珠"),
            ),
        )

        assertEquals("baozhu", result.characterId)
    }

    @Test
    fun `does not cross sentence boundary when relaxing`() {
        val paragraph = paragraph("宝珠走了。旁边的人接着说：“她走了。”")
        val result = resolve(
            paragraph = paragraph,
            segment = dialogue(paragraph, "“她走了。”"),
            characters = listOf(character("baozhu", "宝珠")),
        )

        assertNull(result.characterId)
    }

    @Test
    fun `resolves action subject without speech verb`() {
        val paragraph = paragraph("韦训拨弄了一下手里的金币，正好十枚，“这是？”")
        val result = resolve(
            paragraph = paragraph,
            segment = dialogue(paragraph, "“这是？”"),
            characters = listOf(character("weixun", "韦训")),
        )

        assertEquals("weixun", result.characterId)
    }

    @Test
    fun `resolves pronoun speaker mentioned in previous paragraph`() {
        val first = CanonicalSpeechParagraph(
            0,
            "韦训见她从铺子里出来，特意往她发髻上瞧了瞧，依然只有那支桂花。",
            0,
        )
        val second = CanonicalSpeechParagraph(1, "他狐疑地问：“你买了什么？”", first.text.length)
        val result = LocalCharacterSpeakerResolver.resolve(
            paragraphs = listOf(first, second),
            segments = listOf(
                narration(first, first.text),
                narration(second, "他狐疑地问："),
                dialogue(second, "“你买了什么？”"),
            ),
            characters = listOf(character("weixun", "韦训", voiceGender = "male")),
        ).last()

        assertEquals("weixun", result.characterId)
    }

    @Test
    fun `does not treat a mentioned object as the speaker`() {
        val paragraph = paragraph("他看了韦训一眼，“走吧。”")
        val result = LocalCharacterSpeakerResolver.resolve(
            paragraphs = listOf(paragraph),
            segments = listOf(
                narration(paragraph, "他看了韦训一眼，"),
                dialogue(paragraph, "“走吧。”"),
            ),
            characters = listOf(character("weixun", "韦训", voiceGender = "male")),
        ).last()

        assertNull(result.characterId)
    }

    @Test
    fun `resolves action subject followed by colon`() {
        val paragraph = paragraph("听完之后，杨行简一拍大腿：“糟了，是我疏忽。”")
        val result = resolve(
            paragraph = paragraph,
            segment = dialogue(paragraph, "“糟了，是我疏忽。”"),
            characters = listOf(character("yang", "杨行简")),
        )

        assertEquals("yang", result.characterId)
    }

    @Test
    fun `resolves speaker when verb carries a suffix`() {
        val paragraph = paragraph("宝珠心生警惕，立刻收了泪，照着套话说了一遍：“有去处。”")
        val result = resolve(
            paragraph = paragraph,
            segment = dialogue(paragraph, "“有去处。”"),
            characters = listOf(character("baozhu", "宝珠")),
        )

        assertEquals("baozhu", result.characterId)
    }

    @Test
    fun `does not treat an object in the middle of a clause as the speaker`() {
        val paragraph = paragraph("宝珠摘下头上的桂花枝，让十三郎取出琉璃漆盒，重新放回盒中，自语道：“那人倒提醒了我。”")
        val result = resolve(
            paragraph = paragraph,
            segment = dialogue(paragraph, "“那人倒提醒了我。”"),
            characters = listOf(
                character("baozhu", "宝珠"),
                character("shisan", "十三郎"),
            ),
        )

        assertEquals("baozhu", result.characterId)
    }

    @Test
    fun `resolves zero subject speech carried over from the previous paragraph`() {
        val first = CanonicalSpeechParagraph(
            0,
            "宝珠失魂落魄，一言不发，韦训心想那一鞭并未打中她。",
            0,
        )
        val second = CanonicalSpeechParagraph(1, "问道：“你认识那几个人？”", first.text.length)
        val result = LocalCharacterSpeakerResolver.resolve(
            paragraphs = listOf(first, second),
            segments = listOf(
                narration(first, first.text),
                narration(second, "问道："),
                dialogue(second, "“你认识那几个人？”"),
            ),
            characters = listOf(
                character("baozhu", "宝珠", voiceGender = "female"),
                character("weixun", "韦训", voiceGender = "male"),
            ),
        ).last()

        // 「问道：」没有主语，承接上一段最后出场的人
        assertEquals("weixun", result.characterId)
    }

    private fun resolve(
        paragraph: CanonicalSpeechParagraph,
        segment: ChapterSpeechSegment,
        characters: List<SpeakerCharacter>,
    ): ChapterSpeechSegment = LocalCharacterSpeakerResolver.resolve(
        paragraphs = listOf(paragraph),
        segments = listOf(segment),
        characters = characters,
    ).single()

    private fun paragraph(text: String) = CanonicalSpeechParagraph(0, text, 0)

    private fun dialogue(
        paragraph: CanonicalSpeechParagraph,
        text: String,
    ): ChapterSpeechSegment = segment(paragraph, text, SpeechRoleType.Character)

    private fun narration(
        paragraph: CanonicalSpeechParagraph,
        text: String,
    ): ChapterSpeechSegment = segment(paragraph, text, SpeechRoleType.Narrator)

    private fun segment(
        paragraph: CanonicalSpeechParagraph,
        text: String,
        roleType: SpeechRoleType,
    ): ChapterSpeechSegment {
        val start = paragraph.text.indexOf(text)
        return ChapterSpeechSegment(
            id = "segment-${paragraph.index}-$start",
            analysisId = "analysis",
            bookUrl = "book",
            chapterIndex = 0,
            paragraphIndex = paragraph.index,
            start = start,
            end = start + text.length,
            chapterPosition = start,
            text = text,
            roleType = roleType,
            source = SpeechResolutionSource.Rule,
        )
    }

    private fun character(
        id: String,
        name: String,
        aliases: List<String> = emptyList(),
        voiceGender: String = "unknown",
    ) = SpeakerCharacter(id = id, name = name, aliases = aliases, voiceGender = voiceGender)
}
