package io.legado.app.help.readaloud.resolve

import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.SpeakerCharacter
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType

object LocalCharacterSpeakerResolver {

    const val VERSION = "local-character-resolver-v3"

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

    fun resolve(
        paragraphs: List<CanonicalSpeechParagraph>,
        segments: List<ChapterSpeechSegment>,
        characters: List<SpeakerCharacter>,
    ): List<ChapterSpeechSegment> {
        if (segments.isEmpty() || characters.isEmpty()) return segments
        val paragraphsByIndex = paragraphs.associateBy(CanonicalSpeechParagraph::index)
        val aliases = buildAliasCandidates(characters)
        if (aliases.isEmpty()) return segments

        return segments.map { segment ->
            if (!segment.shouldResolve) return@map segment
            val paragraph = paragraphsByIndex[segment.paragraphIndex] ?: return@map segment
            val start = segment.start.coerceIn(0, paragraph.text.length)
            val end = segment.end.coerceIn(start, paragraph.text.length)
            val before = paragraph.text.substring(0, start).takeLast(CONTEXT_LENGTH)
            val after = paragraph.text.substring(end).take(CONTEXT_LENGTH)
            val character = resolveStrict(aliases, before, after, segment.roleType)
                ?: resolveLoose(aliases, before)
                ?: return@map segment
            segment.copy(
                characterId = character.id,
                characterName = character.name,
                confidence = maxOf(segment.confidence, 0.94f),
                source = SpeechResolutionSource.Local,
            )
        }
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
