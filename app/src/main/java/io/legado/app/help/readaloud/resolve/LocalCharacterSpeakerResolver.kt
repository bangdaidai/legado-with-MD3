package io.legado.app.help.readaloud.resolve

import io.legado.app.domain.model.readaloud.BookVoiceBinding
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.SpeakerCharacter
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.help.readaloud.ReadAloudVoiceTraits

object LocalCharacterSpeakerResolver {

    const val VERSION = "local-character-resolver-v4-pronoun"

    private const val CONTEXT_LENGTH = 64

    /** 说话动词前常见的短修饰语，如「接着道」「缓缓道」「冷笑道」；不允许跨标点 */
    private const val VERB_PREFIX = "[^，,。.！!？?；;：:、“”‘’\"'\\s]{0,4}"

    /**
     * 名字与说话动词之间允许夹的修饰语长度，如「宝珠眼睛亮晶晶的，认真说：」。
     * 允许逗号但不允许句末标点和引号，免得跨句抢别人的台词。
     */
    private const val LOOSE_CUE_LENGTH = 20
    private const val LOOSE_CUE = "[^。.！!？?；;：:“”‘’\"'\\n]{0,$LOOSE_CUE_LENGTH}"

    private const val SPEECH_VERB_CORE = "(?:说道|说|问道|问|答道|答|喊道|喊|叫道|叫|喝道|笑道|" +
        "吼道|叹道|念道|吟道|唱道|回道|应道|续道|道)"
    private const val THOUGHT_VERB_CORE = "(?:心想|心道|暗道|想道|默念)"
    private val speechVerb = "(?:$VERB_PREFIX)?$SPEECH_VERB_CORE"
    private val thoughtVerb = "(?:$VERB_PREFIX)?$THOUGHT_VERB_CORE"

    /**
     * 名字后面只有动作、根本没有说话动词就直接接引号的写法：
     * 「韦训拨弄了一下手里的金币，正好十枚，“这是？”」。
     * 允许逗号，不允许句末标点和引号，免得跨句抢别人的台词。
     */
    private const val ACTION_CUE = "[^。.！!？?；;：:“”‘’\"'\\n]{0,30}"

    /**
     * 小句开头。名字必须在段首或标点之后才可能是动作主语：「他看了韦训一眼，“……”」里的韦训跟在
     * 「了」后面，不算主语。逗号也算小句边界 —— 「韦训问她要不要买，宝珠摇头，“不用了。”」里两个名字
     * 都会命中，[resolveActionSubject] 遇到两个人就放弃，比只认第一个更安全。
     */
    private const val CLAUSE_START = "(?:^|[。.！!？?；;，,、”’\\n])\\s*"

    /** 「他狐疑地问：」这类代词回指，落到最近出现过的同性别角色上 */
    private val malePronounCueRegex = pronounCueRegex("他")
    private val femalePronounCueRegex = pronounCueRegex("她")

    private fun pronounCueRegex(pronoun: String) = Regex(
        "$CLAUSE_START$pronoun(?!们)$LOOSE_CUE" +
            "(?:$SPEECH_VERB_CORE|$THOUGHT_VERB_CORE)\\s*[，,：:\\s]*$"
    )

    fun resolve(
        paragraphs: List<CanonicalSpeechParagraph>,
        segments: List<ChapterSpeechSegment>,
        characters: List<SpeakerCharacter>,
    ): List<ChapterSpeechSegment> {
        if (segments.isEmpty() || characters.isEmpty()) return segments
        val paragraphsByIndex = paragraphs.associateBy(CanonicalSpeechParagraph::index)
        val aliases = buildAliasCandidates(characters)
        if (aliases.isEmpty()) return segments
        val mentions = RecentMentions(aliases)

        return segments.map { segment ->
            val resolved = segment.resolveWith(paragraphsByIndex, aliases, mentions)
            // 只把旁白里出场的人记成「最近出现」，台词内容里提到的名字不算说话人线索
            if (segment.roleType == SpeechRoleType.Narrator) mentions.observe(segment.text)
            resolved
        }
    }

    private fun ChapterSpeechSegment.resolveWith(
        paragraphsByIndex: Map<Int, CanonicalSpeechParagraph>,
        aliases: List<AliasCandidate>,
        mentions: RecentMentions,
    ): ChapterSpeechSegment {
        if (!shouldResolve) return this
        val paragraph = paragraphsByIndex[paragraphIndex] ?: return this
        val from = start.coerceIn(0, paragraph.text.length)
        val to = end.coerceIn(from, paragraph.text.length)
        val before = paragraph.text.substring(0, from).takeLast(CONTEXT_LENGTH)
        val after = paragraph.text.substring(to).take(CONTEXT_LENGTH)
        // 置信度按线索强度递减：说话动词最硬，动作主语和代词回指都是推断，
        // 压到 0.75 以下让「规则 + AI」模式还会去复核一遍
        val (character, resolvedConfidence) =
            resolveStrict(aliases, before, after, roleType)?.let { it to 0.94f }
                ?: resolveLoose(aliases, before)?.let { it to 0.94f }
                ?: resolveActionSubject(aliases, before)?.let { it to 0.74f }
                ?: mentions.resolvePronoun(before)?.let { it to 0.7f }
                ?: return this
        mentions.remember(character)
        return copy(
            characterId = character.id,
            characterName = character.name,
            confidence = maxOf(confidence, resolvedConfidence),
            source = SpeechResolutionSource.Local,
        )
    }

    /** 名字紧贴说话动词的写法，命中多个角色时宁可放弃也不瞎猜 */
    private fun resolveStrict(
        aliases: List<AliasCandidate>,
        before: String,
        after: String,
        roleType: SpeechRoleType,
    ): SpeakerCharacter? = aliases.asSequence()
        .filter { candidate ->
            candidate.matchesBefore(before, roleType) || candidate.matchesAfter(after)
        }
        .map(AliasCandidate::character)
        .distinctBy(SpeakerCharacter::id)
        .take(2)
        .toList()
        .singleOrNull()

    /**
     * 严格匹配要求名字紧贴说话动词，「宝珠眼睛亮晶晶的，认真说：」这种带长修饰语的写法会整句漏掉，
     * 最后退成旁白音。这里放宽中间的修饰语，多个名字都命中时取离说话动词最近的那个。
     */
    private fun resolveLoose(
        aliases: List<AliasCandidate>,
        before: String,
    ): SpeakerCharacter? = aliases
        .mapNotNull { candidate ->
            candidate.looseBeforeMatchStart(before)?.let { it to candidate.character }
        }
        .maxByOrNull { it.first }
        ?.second

    private val ChapterSpeechSegment.shouldResolve: Boolean
        get() = !userLocked &&
            characterId == null &&
            (roleType == SpeechRoleType.Character || roleType == SpeechRoleType.Thought)

    /**
     * 名字在小句开头、后面只有动作就直接接引号：
     * 「韦训拨弄了一下手里的金币，正好十枚，“这是？”」。
     * 命中两个角色就放弃，免得把「甲看了乙一眼，“……”」认成乙在说话。
     */
    private fun resolveActionSubject(
        aliases: List<AliasCandidate>,
        before: String,
    ): SpeakerCharacter? = aliases.asSequence()
        .filter { it.matchesActionSubject(before) }
        .map(AliasCandidate::character)
        .distinctBy(SpeakerCharacter::id)
        .take(2)
        .toList()
        .singleOrNull()

    /**
     * 顺序扫描时记住最近出现过的男/女角色，用来解「他/她 + 说话动词」这种跨段落代词回指：
     * 「韦训见她从铺子里出来……」下一段「他狐疑地问：“你买了什么？”」。
     *
     * 性别拿不准就不记，宁可掉回旁白音也不瞎猜。
     */
    private class RecentMentions(private val aliases: List<AliasCandidate>) {

        private var recentMale: SpeakerCharacter? = null
        private var recentFemale: SpeakerCharacter? = null

        fun observe(text: String) {
            aliases.forEach { candidate ->
                if (candidate.alias in text) remember(candidate.character)
            }
        }

        fun remember(character: SpeakerCharacter) {
            when (character.speakingGender()) {
                ReadAloudVoiceTraits.GENDER_MALE -> recentMale = character
                ReadAloudVoiceTraits.GENDER_FEMALE -> recentFemale = character
            }
        }

        fun resolvePronoun(before: String): SpeakerCharacter? = when {
            malePronounCueRegex.containsMatchIn(before) -> recentMale
            femalePronounCueRegex.containsMatchIn(before) -> recentFemale
            else -> null
        }
    }

    /** 角色卡写明的性别优先，其次从男主/女主这类定位推 */
    private fun SpeakerCharacter.speakingGender(): String? = when (voiceGender) {
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

    private fun buildAliasCandidates(characters: List<SpeakerCharacter>): List<AliasCandidate> {
        val ownersByAlias = linkedMapOf<String, MutableList<SpeakerCharacter>>()
        characters.forEach { character ->
            (listOf(character.name) + character.aliases)
                .map { it.normalizeAlias() }
                .filter { it.isNotBlank() }
                .distinct()
                .forEach { alias -> ownersByAlias.getOrPut(alias, ::mutableListOf) += character }
        }
        return ownersByAlias.mapNotNull { (alias, owners) ->
            owners.distinctBy(SpeakerCharacter::id).singleOrNull()?.let { AliasCandidate(alias, it) }
        }.sortedByDescending { it.alias.length }
    }

    private fun String.normalizeAlias(): String = trim()
        .trim('“', '”', '‘', '’', '"', '\'', '《', '》', '，', ',', '。', '：', ':')
        .replace(Regex("\\s+"), "")

    private data class AliasCandidate(
        val alias: String,
        val character: SpeakerCharacter,
    ) {
        private val escapedAlias = Regex.escape(alias)
        private val beforeSpeechRegex = Regex(
            "(?<![\\p{L}\\p{N}_])$escapedAlias\\s*(?:$speechVerb|$thoughtVerb)?\\s*[：:]\\s*$"
        )
        private val beforeVerbRegex = Regex(
            "(?<![\\p{L}\\p{N}_])$escapedAlias\\s*(?:$speechVerb|$thoughtVerb)\\s*[，,\\s]*$"
        )
        private val afterRegex = Regex(
            "^[，,。.!！?？\\s]*(?<![\\p{L}\\p{N}_])$escapedAlias\\s*(?:$speechVerb|$thoughtVerb)"
        )
        private val beforeLooseRegex = Regex(
            "(?<![\\p{L}\\p{N}_])$escapedAlias$LOOSE_CUE" +
                "(?:$SPEECH_VERB_CORE|$THOUGHT_VERB_CORE)\\s*[：:]\\s*$"
        )
        private val actionSubjectRegex = Regex("$CLAUSE_START$escapedAlias$ACTION_CUE$")

        fun matchesActionSubject(context: String): Boolean =
            actionSubjectRegex.containsMatchIn(context)

        /** 命中则返回名字在上下文里的起始下标，用来挑离说话动词最近的那个名字 */
        fun looseBeforeMatchStart(context: String): Int? =
            beforeLooseRegex.find(context)?.range?.first

        fun matchesBefore(context: String, roleType: SpeechRoleType): Boolean {
            val regex = if (roleType == SpeechRoleType.Thought) {
                Regex(
                    "(?<![\\p{L}\\p{N}_])$escapedAlias\\s*$thoughtVerb\\s*[，,：:\\s]*$"
                )
            } else {
                null
            }
            return regex?.containsMatchIn(context) == true ||
                beforeSpeechRegex.containsMatchIn(context) ||
                beforeVerbRegex.containsMatchIn(context)
        }

        fun matchesAfter(context: String): Boolean =
            afterRegex.containsMatchIn(context)
    }
}
