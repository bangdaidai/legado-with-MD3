package io.legado.app.domain.usecase

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.gateway.AiTextGateway
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiMessage
import io.legado.app.domain.model.AiMessageRole
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.AiTaskPresetConfig
import io.legado.app.domain.model.AiTaskType
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysisResult
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.SpeechAnalysisMode
import io.legado.app.domain.model.readaloud.SpeechAnalysisStatus
import io.legado.app.domain.model.readaloud.SpeechEmotion
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.help.readaloud.segment.AiSpeechAtom
import io.legado.app.help.readaloud.segment.AiSpeechAtomizer
import io.legado.app.help.readaloud.segment.RuleBasedSpeechSegmenter
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid
import java.util.concurrent.ConcurrentHashMap

class RefineSpeechWithAiUseCase(
    private val aiProfileGateway: AiProfileGateway,
    private val aiTextGateway: AiTextGateway,
    private val bookKnowledgeGateway: BookKnowledgeGateway,
    private val chapterSpeechGateway: ChapterSpeechGateway,
) {

    /**
     * 本书级 AI 失败冷却：同一本书任意一章 AI 识别失败后，后续章节（含听书预加载的下一章）
     * 直接跳过 AI、回落规则，避免每章都各自等一次超时。
     *
     * 之前是按章冷却（[ChapterSpeechAnalysisResult.isAiCoolingDown]），但分析记录按
     * (bookUrl, chapterIndex, resolverVersion) 分章独立，第一章失败不会让第二章跳过 AI，
     * 于是预加载 10 章会各发一次慢 AI 请求。改成本书级后，第一章失败即整本书进入冷却。
     *
     * 注意：换模型/改 prompt 会因 resolverVersion 变化落到新分析记录，但本书冷却仍按 bookUrl 计；
     * 分镜页「重新分析」会调用 [clearAiCooldown] 主动解除，避免被冷却挡住。
     */
    private val bookAiCooldownUntil = ConcurrentHashMap<String, Long>()

    fun clearAiCooldown(bookUrl: String) {
        bookAiCooldownUntil.remove(bookUrl)
    }

    suspend fun resolverVersion(bookUrl: String, mode: SpeechAnalysisMode): String {
        if (mode == SpeechAnalysisMode.Rule) return RuleBasedSpeechSegmenter.VERSION
        val preset = resolvePreset()
        val profiles = knownProfiles(bookUrl)
        val characterRevision = profiles
            .sortedBy(BookCharacterProfile::id)
            .joinToString("|") { "${it.id}:${it.updatedAt}" }
        val promptHash = MD5Utils.md5Encode(systemPrompt(preset, mode))
        return listOf(
            RuleBasedSpeechSegmenter.VERSION,
            VERSION,
            mode.storageValue,
            preset.model.id,
            promptHash,
            MD5Utils.md5Encode(characterRevision),
        ).joinToString(":")
    }

    suspend operator fun invoke(
        analysisResult: ChapterSpeechAnalysisResult,
        paragraphs: List<CanonicalSpeechParagraph>,
        mode: SpeechAnalysisMode,
        now: Long = System.currentTimeMillis(),
    ): ChapterSpeechAnalysisResult {
        if (mode == SpeechAnalysisMode.Rule) return analysisResult
        val bookUrl = analysisResult.analysis.bookUrl
        if (bookAiCooldownUntil[bookUrl]?.let { now < it } == true) return analysisResult
        if (
            mode == SpeechAnalysisMode.AiUnderstanding &&
            analysisResult.fromCache &&
            analysisResult.segments.all { it.userLocked || it.source == SpeechResolutionSource.Ai }
        ) return analysisResult
        val profiles = knownProfiles(analysisResult.analysis.bookUrl)
        val preset = resolvePreset()
        val refined = try {
            when (mode) {
                SpeechAnalysisMode.Rule -> analysisResult.segments
                SpeechAnalysisMode.RuleWithAi -> completeRuleSegments(
                    analysisResult = analysisResult,
                    profiles = profiles,
                    preset = preset,
                    now = now,
                )
                SpeechAnalysisMode.AiUnderstanding -> {
                    if (analysisResult.segments.any(ChapterSpeechSegment::userLocked)) {
                        completeRuleSegments(analysisResult, profiles, preset, now)
                    } else {
                        understandAtoms(analysisResult, paragraphs, profiles, preset, now)
                    }
                }
            }
        } catch (e: CancellationException) {
            // 用户翻页/停止朗读导致的取消不是失败，不能因此把整章锁进冷却
            throw e
        } catch (e: Throwable) {
            markAiFailed(analysisResult, e, now)
            bookAiCooldownUntil[bookUrl] = now + AI_FAILURE_COOLDOWN_MS
            throw e
        }
        val status = if (refined.any { segment ->
                segment.characterId == null && segment.roleType in setOf(
                    SpeechRoleType.Character,
                    SpeechRoleType.Thought,
                )
            }) {
            SpeechAnalysisStatus.Partial
        } else {
            SpeechAnalysisStatus.Success
        }
        val analysis = analysisResult.analysis.copy(status = status, error = "", updatedAt = now)
        chapterSpeechGateway.saveAnalysis(analysis, refined)
        // AI 这次跑通了，解除本书冷却，保证后续章节能正常用多角色
        bookAiCooldownUntil.remove(bookUrl)
        return analysisResult.copy(
            analysis = analysis,
            segments = refined,
            fromCache = false,
        )
    }

    /**
     * 把 AI 失败写进分析记录：分段保持规则结果不动，只标状态和原因。
     *
     * 写库本身失败不能盖掉原始异常，所以这里吞掉写库错误。
     */
    private suspend fun markAiFailed(
        analysisResult: ChapterSpeechAnalysisResult,
        error: Throwable,
        now: Long,
    ) {
        val analysis = analysisResult.analysis.copy(
            status = SpeechAnalysisStatus.Failed,
            error = error.localizedMessage.orEmpty().take(MAX_ERROR_LENGTH),
            updatedAt = now,
        )
        runCatching { chapterSpeechGateway.saveAnalysis(analysis, analysisResult.segments) }
    }

    private suspend fun completeRuleSegments(
        analysisResult: ChapterSpeechAnalysisResult,
        profiles: List<BookCharacterProfile>,
        preset: AiTaskPresetConfig,
        now: Long,
    ): List<ChapterSpeechSegment> {
        val candidates = analysisResult.segments.filter { segment ->
            !segment.userLocked && segment.source != SpeechResolutionSource.Ai && (
                segment.confidence < HYBRID_CONFIDENCE_THRESHOLD ||
                    segment.characterId == null && segment.roleType in setOf(
                        SpeechRoleType.Character,
                        SpeechRoleType.Thought,
                    )
                )
        }
        if (candidates.isEmpty()) return analysisResult.segments
        val updates = linkedMapOf<String, AiSegmentDecision>()
        candidates.chunkByTextLength(MAX_CHUNK_CHARS) { it.text }.forEach { chunk ->
            val payload = mapOf(
                "characters" to profiles.map { it.toPromptMap() },
                "segments" to chunk.map { segment ->
                    mapOf(
                        "segmentId" to segment.id,
                        "text" to segment.text,
                        "roleType" to segment.roleType.storageValue,
                        "characterId" to segment.characterId,
                        "emotion" to segment.emotion,
                        "confidence" to segment.confidence,
                    )
                },
            )
            parseSegmentDecisions(generate(preset, SpeechAnalysisMode.RuleWithAi, payload))
                .forEach { decision ->
                    require(decision.segmentId in chunk.map(ChapterSpeechSegment::id)) {
                        "AI returned an unknown segmentId: ${decision.segmentId}"
                    }
                    require(updates.put(decision.segmentId, decision) == null) {
                        "AI returned duplicate segmentId: ${decision.segmentId}"
                    }
                    require(decision.characterId == null || profiles.any { it.id == decision.characterId }) {
                        "AI returned an unknown characterId: ${decision.characterId}"
                    }
                }
            require(updates.keys.containsAll(chunk.map(ChapterSpeechSegment::id))) {
                "AI did not return every requested segment"
            }
        }
        val allProfiles = ensureDraftProfiles(
            bookUrl = analysisResult.analysis.bookUrl,
            profiles = profiles,
            speakers = updates.values.mapNotNull { it.newSpeaker() },
            now = now,
        )
        val profilesById = allProfiles.associateBy(BookCharacterProfile::id)
        val profilesByName = allProfiles.associateBy { it.name.trim() }
        return analysisResult.segments.map { segment ->
            if (segment !in candidates) return@map segment
            val decision = updates[segment.id]
            val roleType = decision?.roleType ?: segment.roleType
            val character = decision?.characterId?.let(profilesById::get)
                ?: decision?.speakerName?.trim()?.let(profilesByName::get)
            segment.copy(
                roleType = roleType,
                characterId = if (roleType == SpeechRoleType.Narrator) null else character?.id,
                characterName = if (roleType == SpeechRoleType.Narrator) "" else character?.name.orEmpty(),
                emotion = decision?.emotion ?: segment.emotion,
                confidence = decision?.confidence ?: segment.confidence,
                source = SpeechResolutionSource.Ai,
                updatedAt = now,
            )
        }
    }

    /** AI 认出的人物声音，但没命中已有角色卡时，返回待建草稿卡的「名字 to 性别」。 */
    private fun AiSegmentDecision.newSpeaker(): Pair<String, String>? {
        if (characterId != null) return null
        if (roleType != SpeechRoleType.Character && roleType != SpeechRoleType.Thought) return null
        return speakerName?.let { it to speakerGender }
    }

    private fun AiAtomGroup.newSpeaker(): Pair<String, String>? {
        if (characterId != null) return null
        if (roleType != SpeechRoleType.Character && roleType != SpeechRoleType.Thought) return null
        return speakerName?.let { it to speakerGender }
    }

    /**
     * 把 AI 认出但还没进角色卡的说话人落成草稿角色卡（临时说话人）。
     *
     * 草稿卡在配音页单独一栏、可以直接绑音色、在朗读里参与路由，但只有用户点「转正」才会变成正式角色卡。
     * 按名字去重，因为 `book_character_profiles` 对 (bookUrl, name) 有唯一索引。
     */
    private suspend fun ensureDraftProfiles(
        bookUrl: String,
        profiles: List<BookCharacterProfile>,
        speakers: List<Pair<String, String>>,
        now: Long,
    ): List<BookCharacterProfile> {
        if (speakers.isEmpty()) return profiles
        val existingNames = profiles.mapTo(hashSetOf()) { it.name.trim() }
        val created = mutableListOf<BookCharacterProfile>()
        speakers.distinctBy { it.first.trim() }.forEach { (name, gender) ->
            val trimmed = name.trim()
            if (trimmed.isBlank() || !existingNames.add(trimmed)) return@forEach
            val profile = BookCharacterProfile(
                id = Uuid.random().toString(),
                bookUrl = bookUrl,
                name = trimmed,
                voiceGender = gender,
                status = BookCharacterProfile.STATUS_DRAFT,
                source = BookCharacterProfile.SOURCE_AI,
                confidence = DRAFT_SPEAKER_CONFIDENCE,
                createdAt = now,
                updatedAt = now,
            )
            bookKnowledgeGateway.upsertCharacterProfile(profile)
            created += profile
        }
        return profiles + created
    }

    private suspend fun understandAtoms(
        analysisResult: ChapterSpeechAnalysisResult,
        paragraphs: List<CanonicalSpeechParagraph>,
        profiles: List<BookCharacterProfile>,
        preset: AiTaskPresetConfig,
        now: Long,
    ): List<ChapterSpeechSegment> {
        val atoms = paragraphs.flatMap(AiSpeechAtomizer::atomize)
        if (atoms.isEmpty()) return analysisResult.segments
        var knownProfiles = profiles
        val result = mutableListOf<ChapterSpeechSegment>()
        atoms.chunkByTextLength(MAX_CHUNK_CHARS) { it.text }.forEach { chunk ->
            val payload = mapOf(
                "characters" to knownProfiles.map { it.toPromptMap() },
                "atoms" to chunk.map { atom ->
                    mapOf("atomId" to atom.id, "text" to atom.text)
                },
            )
            val groups = parseAtomGroups(generate(preset, SpeechAnalysisMode.AiUnderstanding, payload))
            validateCoverage(chunk, groups)
            val knownIds = knownProfiles.mapTo(hashSetOf(), BookCharacterProfile::id)
            require(groups.all { group ->
                group.characterId == null || group.characterId in knownIds
            }) { "AI returned an unknown characterId" }
            knownProfiles = ensureDraftProfiles(
                bookUrl = analysisResult.analysis.bookUrl,
                profiles = knownProfiles,
                speakers = groups.mapNotNull { it.newSpeaker() },
                now = now,
            )
            val profilesById = knownProfiles.associateBy(BookCharacterProfile::id)
            val profilesByName = knownProfiles.associateBy { it.name.trim() }
            val atomsById = chunk.associateBy(AiSpeechAtom::id)
            groups.forEach { group ->
                val groupedAtoms = group.atomIds.map(atomsById::getValue)
                require(groupedAtoms.map(AiSpeechAtom::paragraphIndex).distinct().size == 1) {
                    "AI cannot merge atoms across paragraphs"
                }
                val first = groupedAtoms.first()
                val last = groupedAtoms.last()
                val paragraph = paragraphs.first { it.index == first.paragraphIndex }
                val character = group.characterId?.let(profilesById::get)
                    ?: group.speakerName?.trim()?.let(profilesByName::get)
                result += ChapterSpeechSegment(
                    id = SpeechIdentity.segmentId(
                        analysisId = analysisResult.analysis.id,
                        paragraphIndex = first.paragraphIndex,
                        start = first.start,
                        end = last.end,
                    ),
                    analysisId = analysisResult.analysis.id,
                    bookUrl = analysisResult.analysis.bookUrl,
                    chapterIndex = analysisResult.analysis.chapterIndex,
                    paragraphIndex = first.paragraphIndex,
                    start = first.start,
                    end = last.end,
                    chapterPosition = paragraph.chapterPosition + first.start,
                    text = paragraph.text.substring(first.start, last.end),
                    roleType = group.roleType,
                    characterId = if (group.roleType == SpeechRoleType.Narrator) null else character?.id,
                    characterName = if (group.roleType == SpeechRoleType.Narrator) "" else character?.name.orEmpty(),
                    emotion = group.emotion,
                    confidence = group.confidence,
                    source = SpeechResolutionSource.Ai,
                    createdAt = now,
                    updatedAt = now,
                )
            }
        }
        return result.sortedWith(compareBy(ChapterSpeechSegment::paragraphIndex, ChapterSpeechSegment::start))
    }

    private suspend fun generate(
        preset: AiTaskPresetConfig,
        mode: SpeechAnalysisMode,
        payload: Any,
    ): String = aiTextGateway.generate(
        AiGenerateRequest(
            model = preset.model,
            messages = listOf(
                AiMessage(AiMessageRole.SYSTEM, systemPrompt(preset, mode)),
                AiMessage(AiMessageRole.USER, GSON.toJson(payload)),
            ),
            // 说话人归因是结构化抽取，思考没有收益，却会把 max_tokens 花光 ——
            // 思考型模型于是只回 reasoning_content、正文为空，整块分析白跑。
            params = preset.params.copy(
                temperature = 0f,
                reasoningLevel = AiReasoningLevel.OFF,
                maxOutputTokens = maxOf(preset.params.maxOutputTokens ?: 0, MIN_OUTPUT_TOKENS),
            ),
            taskType = AiTaskType.ANALYZE_SPEECH,
        )
    ).getOrThrow().text

    private suspend fun resolvePreset(): AiTaskPresetConfig =
        aiProfileGateway.getTaskPreset(AiTaskType.ANALYZE_SPEECH)
            ?: aiProfileGateway.getTaskPreset(AiTaskType.CHAT)
            ?: error("No AI model configured for speech analysis")

    /**
     * 正式角色卡 + 草稿角色卡（临时说话人）。
     *
     * 草稿也要喂给 AI，否则同一个说话人每章都会被当成新人重新建卡。
     */
    private suspend fun knownProfiles(bookUrl: String): List<BookCharacterProfile> =
        bookKnowledgeGateway.getCharacterProfiles(bookUrl, 200, includeDrafts = true)
            .filter {
                it.status == BookCharacterProfile.STATUS_ACTIVE ||
                    it.status == BookCharacterProfile.STATUS_DRAFT
            }

    private fun systemPrompt(preset: AiTaskPresetConfig, mode: SpeechAnalysisMode): String {
        val custom = preset.promptTemplate.takeIf {
            preset.taskType == AiTaskType.ANALYZE_SPEECH && it.isNotBlank()
        }
        return buildString {
            append(custom ?: DEFAULT_PROMPT)
            append("\nReturn only one JSON object. Never rewrite text and never invent IDs.")
            append(SPEAKER_RULES)
            if (mode == SpeechAnalysisMode.RuleWithAi) {
                append("\nReturn {\"segments\":[{\"segmentId\":string,\"roleType\":")
                append("\"narrator|character|thought|unknown\",\"characterId\":string|null,")
                append("\"speakerName\":string|null,\"speakerGender\":\"male|female|unknown\",")
                append("\"emotion\":\"neutral|cheerful|sad|angry|fearful|surprised|disgusted|whispering|calm\",")
                append("\"confidence\":number}]}. Return one decision for every input segment.")
            } else {
                append("\nGroup every atom exactly once and in input order. Groups cannot cross paragraphs.")
                append("\nReturn {\"segments\":[{\"atomIds\":[string],\"roleType\":")
                append("\"narrator|character|thought|unknown\",\"characterId\":string|null,")
                append("\"speakerName\":string|null,\"speakerGender\":\"male|female|unknown\",")
                append("\"emotion\":\"neutral|cheerful|sad|angry|fearful|surprised|disgusted|whispering|calm\",")
                append("\"confidence\":number}]}.")
            }
        }
    }

    private fun parseSegmentDecisions(text: String): List<AiSegmentDecision> =
        parseRoot(text).getAsJsonArray("segments").map { element ->
            val item = element.asJsonObject
            AiSegmentDecision(
                segmentId = item.requiredString("segmentId"),
                roleType = item.roleType(),
                characterId = item.optionalString("characterId"),
                speakerName = item.speakerName(),
                speakerGender = item.speakerGender(),
                emotion = item.emotion(),
                confidence = item.confidence(),
            )
        }

    private fun parseAtomGroups(text: String): List<AiAtomGroup> =
        parseRoot(text).getAsJsonArray("segments").map { element ->
            val item = element.asJsonObject
            AiAtomGroup(
                atomIds = item.getAsJsonArray("atomIds").map { it.asString },
                roleType = item.roleType(),
                characterId = item.optionalString("characterId"),
                speakerName = item.speakerName(),
                speakerGender = item.speakerGender(),
                emotion = item.emotion(),
                confidence = item.confidence(),
            )
        }

    private fun parseRoot(text: String): JsonObject {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        require(start >= 0 && end > start) { "AI returned invalid JSON" }
        return JsonParser.parseString(text.substring(start, end + 1)).asJsonObject
    }

    private fun JsonObject.roleType(): SpeechRoleType {
        val value = requiredString("roleType")
        require(value in SpeechRoleType.entries.map(SpeechRoleType::storageValue)) {
            "AI returned invalid roleType: $value"
        }
        return SpeechRoleType.fromStorage(value)
    }

    private fun JsonObject.emotion(): String =
        SpeechEmotion.fromStorage(optionalString("emotion").orEmpty()).storageValue

    /** 正文给出的稳定称呼；泛称（大汉、老捕头之类）一律当路人，不建卡。 */
    private fun JsonObject.speakerName(): String? =
        optionalString("speakerName")?.trim()?.takeIf { it.isNotBlank() && it.length <= 24 }

    private fun JsonObject.speakerGender(): String {
        val value = optionalString("speakerGender")?.trim().orEmpty()
        return if (value in BookCharacterProfile.ALL_VOICE_GENDERS) {
            value
        } else {
            BookCharacterProfile.VOICE_GENDER_UNKNOWN
        }
    }

    private fun JsonObject.confidence(): Float =
        get("confidence")?.takeUnless { it.isJsonNull }?.asFloat?.coerceIn(0f, 1f) ?: 0f

    private fun JsonObject.requiredString(name: String): String =
        optionalString(name)?.takeIf(String::isNotBlank) ?: error("AI response misses $name")

    private fun JsonObject.optionalString(name: String): String? =
        get(name)?.takeUnless { it.isJsonNull }?.asString

    private fun validateCoverage(atoms: List<AiSpeechAtom>, groups: List<AiAtomGroup>) {
        require(groups.isNotEmpty()) { "AI returned no speech groups" }
        val returned = groups.flatMap(AiAtomGroup::atomIds)
        require(returned == atoms.map(AiSpeechAtom::id)) {
            "AI atom coverage is incomplete, duplicated, or out of order"
        }
    }

    private fun BookCharacterProfile.toPromptMap(): Map<String, Any?> = mapOf(
        "characterId" to id,
        "name" to name,
        "aliasesJson" to aliasesJson,
        "role" to role,
        "voiceGender" to voiceGender,
        "voiceAgeBand" to voiceAgeBand,
        "personality" to personality,
    )

    private fun <T> List<T>.chunkByTextLength(
        maxLength: Int,
        text: (T) -> String,
    ): List<List<T>> {
        if (isEmpty()) return emptyList()
        val result = mutableListOf<MutableList<T>>()
        var current = mutableListOf<T>()
        var length = 0
        forEach { item ->
            val itemLength = text(item).length
            if (current.isNotEmpty() && length + itemLength > maxLength) {
                result += current
                current = mutableListOf()
                length = 0
            }
            current += item
            length += itemLength
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    private data class AiSegmentDecision(
        val segmentId: String,
        val roleType: SpeechRoleType,
        val characterId: String?,
        val speakerName: String?,
        val speakerGender: String,
        val emotion: String,
        val confidence: Float,
    )

    private data class AiAtomGroup(
        val atomIds: List<String>,
        val roleType: SpeechRoleType,
        val characterId: String?,
        val speakerName: String?,
        val speakerGender: String,
        val emotion: String,
        val confidence: Float,
    )

    companion object {
        const val VERSION = "ai-speech-analysis-v2"
        private const val MAX_CHUNK_CHARS = 6_000

        /** 一块 6000 字要按段返回 JSON，输出上限低于这个数就会被截断 */
        private const val MIN_OUTPUT_TOKENS = 8_000
        private const val HYBRID_CONFIDENCE_THRESHOLD = 0.75f
        private const val DRAFT_SPEAKER_CONFIDENCE = 0.6f

        /** AI 失败后多久之内不再重试，10 分钟够覆盖「反复点段落起读」这种连续操作 */
        private const val AI_FAILURE_COOLDOWN_MS = 10 * 60 * 1000L

        /** 失败原因只用于排障提示，截断避免把整段响应写进库 */
        private const val MAX_ERROR_LENGTH = 200
        private const val DEFAULT_PROMPT =
            "Analyze fiction speech for text-to-speech. Distinguish narration, spoken dialogue, " +
                "internal thought and unknown speech. Resolve speakers only from the supplied " +
                "character IDs, infer emotion conservatively, and use null when uncertain."

        /**
         * 说话人归因规则。要点：别名必须映射回已有身份、泛称当路人、性别必须有原文依据。
         * 允许返回 characterId=null + speakerName，客户端会为它建一张草稿角色卡。
         */
        private const val SPEAKER_RULES =
            "\nSpeaker attribution rules:" +
                "\n- Prefer an existing characterId. Nicknames, online handles, titles and childhood " +
                "names are labels of an existing person: if the text maps such a label to a known " +
                "character, reuse that characterId instead of reporting a new speaker." +
                "\n- When the speaker is clearly a person but matches no known character, set " +
                "characterId to null and put the stable name, nickname or unique title in " +
                "speakerName. The client will create a draft character for it." +
                "\n- Generic labels such as a big man, a guard, an old constable, or a passer-by are " +
                "not stable speakers: leave both characterId and speakerName null." +
                "\n- speakerGender must be backed by explicit wording in the text such as a gendered " +
                "pronoun or a gendered form of address. Names, surnames, titles and occupations are " +
                "not gender evidence; return unknown instead of guessing." +
                "\n- A high confidence value never substitutes for evidence." +
                "\n- Narration and other non-person voices: characterId null, speakerName null, " +
                "speakerGender unknown."
    }
}
