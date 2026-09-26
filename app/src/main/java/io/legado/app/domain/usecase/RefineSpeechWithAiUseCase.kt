package io.legado.app.domain.usecase

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.gateway.AiTextGateway
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.AiGenerateRequest
import io.legado.app.domain.model.AiGenerateResponse
import io.legado.app.domain.model.AiGenerationParams
import io.legado.app.domain.model.AiMessage
import io.legado.app.domain.model.AiMessageRole
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.AiTaskPresetConfig
import io.legado.app.domain.model.AiTaskType
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysisResult
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.ContentSplitPolicy
import io.legado.app.domain.model.readaloud.SpeechAnalysisMode
import io.legado.app.domain.model.readaloud.SpeechAnalysisStatus
import io.legado.app.domain.model.readaloud.SpeechEmotion
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.help.readaloud.segment.AiSpeechAtom
import io.legado.app.help.readaloud.segment.AiSpeechAtomizer
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * 同一本书的 AI 分析串行锁。
     *
     * 朗读服务起播和分镜页点开当前章会并发调用本 use case，而「冷却判断 → AI 调用 → 冷却写入」
     * 之间不是原子的。多个并发请求都会先通过冷却检查、各自发一次完整 AI 请求（每个还带 3 次
     * 内部重试），于是出现「同一章 AI 失败回落规则弹好几次、一直转圈」。
     *
     * 用按 bookUrl 的锁把整段包住：第一个请求跑 AI，其余在锁上等待，进入后发现冷却已置位就直接
     * 回落规则，不再重发 AI。
     */
    private val bookLocks = ConcurrentHashMap<String, Mutex>()

    fun clearAiCooldown(bookUrl: String) {
        bookAiCooldownUntil.remove(bookUrl)
    }

    /**
     * 给缺少场景分组的分段补上场景：按章分段（含文本顺序）交给 AI 切分成连续的场景组。
     *
     * 场景只服务分镜页浏览，朗读链路不依赖它，所以刻意与 [invoke] 分开：
     * - 不在本书 AI 锁内、不进失败冷却 —— 场景拆坏了也不能拖慢起播或毁掉说话人分析；
     * - 整段调用失败或单个分块校验不过时原样返回 / 跳过该分块，调用方按「无场景」降级展示。
     */
    suspend fun assignScenes(
        segments: List<ChapterSpeechSegment>,
        reasoningLevel: AiReasoningLevel = AiReasoningLevel.OFF,
    ): List<ChapterSpeechSegment> {
        if (segments.size < 2 || segments.none { it.sceneIndex == 0 }) return segments
        val preset = runCatching { resolvePreset() }.getOrNull() ?: return segments
        val result = segments.toMutableList()
        // 全局场景序号按分块连续递增；某个分块失败只影响它自己
        var nextSceneIndex = 1
        // chunkByTextLength 保序，块内下标 + 块起始偏移 = 全量列表位置
        var chunkOffset = 0
        segments.chunkByTextLength(MAX_CHUNK_CHARS) { it.text }.forEach { chunk ->
            val ids = chunk.map(ChapterSpeechSegment::id)
            val scenes = runCatching {
                parseScenes(
                    text = generateScenes(preset, chunk, reasoningLevel),
                    expectedIds = ids,
                )
            }.getOrNull()
            if (scenes == null) {
                chunkOffset += chunk.size
                return@forEach
            }
            val idPositions = chunk.withIndex().associate { (pos, seg) -> seg.id to chunkOffset + pos }
            scenes.forEach { scene ->
                val current = nextSceneIndex
                val title = scene.title
                scene.segmentIds.forEach { id ->
                    val pos = idPositions[id] ?: return@forEach
                    val segment = result[pos]
                    result[pos] = segment.copy(
                        sceneIndex = current,
                        sceneTitle = title,
                        updatedAt = segment.updatedAt,
                    )
                }
                nextSceneIndex++
            }
            chunkOffset += chunk.size
        }
        return result
    }

    suspend fun resolverVersion(
        bookUrl: String,
        mode: SpeechAnalysisMode,
        policy: ContentSplitPolicy,
    ): String {
        if (mode == SpeechAnalysisMode.Rule) return ruleResolverVersion(policy)
        val preset = resolvePreset()
        val profiles = knownProfiles(bookUrl)
        val characterRevision = profiles
            // AI 分析自建的草稿卡是分析的产物：计入版本号会让每次分析都让自己下一次的
            // 缓存失效（草稿卡落库 → revision 变 → 同章下一次朗读 key 对不上 → 重新
            // 分析 → 又建卡……死循环）。只有用户手动维护的角色（转正/编辑/新增正式卡）
            // 才构成「角色库变化」，需要让旧分析过期。
            .filter { it.status != BookCharacterProfile.STATUS_DRAFT }
            .sortedBy(BookCharacterProfile::id)
            .joinToString("|") { "${it.id}:${it.updatedAt}" }
        val promptHash = MD5Utils.md5Encode(systemPrompt(preset, mode))
        return listOf(
            // 与纯规则分段共用同一前缀，AI 结果才不会被误判成规则模式
            ruleResolverVersion(policy),
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
        reasoningLevel: AiReasoningLevel = AiReasoningLevel.OFF,
        policy: ContentSplitPolicy = ContentSplitPolicy.SentenceLevel,
        now: Long = System.currentTimeMillis(),
        source: String = "朗读",
    ): ChapterSpeechAnalysisResult {
        if (mode == SpeechAnalysisMode.Rule) return analysisResult
        val bookUrl = analysisResult.analysis.bookUrl
        // 同一本书串行化：冷却判断、AI 调用、冷却写入都在锁内，
        // 避免朗读与分镜页并发各自发一次 AI（竞态下冷却还没写入就都通过了检查）。
        // 锁按"书+章"分粒度：全书一把锁会让预合成的第 8 章分析阻塞第 7 章的朗读分析
        // （用户实测等 72 秒）；同章仍互斥（防朗读与分镜页并发重复分析）
        val guard = bookLocks.getOrPut("$bookUrl#${analysisResult.analysis.chapterIndex}") { Mutex() }
        return guard.withLock {
            if (bookAiCooldownUntil[bookUrl]?.let { now < it } == true) return@withLock analysisResult
            // 等锁期间（上一轮的分析在 NonCancellable 里还在跑）可能已把本章 AI 结果
            // 落库：直接复用最新缓存，否则补发轮会拿着进锁前的规则快照再发一次 AI
            val latest = runCatching {
                chapterSpeechGateway.getAnalysis(
                    bookUrl = analysisResult.analysis.bookUrl,
                    chapterIndex = analysisResult.analysis.chapterIndex,
                    contentHash = analysisResult.analysis.contentHash,
                    resolverVersion = analysisResult.analysis.resolverVersion,
                )
            }.getOrNull()
            if (latest != null &&
                latest.id == analysisResult.analysis.id &&
                latest.updatedAt > analysisResult.analysis.updatedAt &&
                latest.status != SpeechAnalysisStatus.Pending &&
                latest.status != SpeechAnalysisStatus.Running
            ) {
                val latestSegments = runCatching {
                    chapterSpeechGateway.getSegments(latest.id)
                }.getOrNull().orEmpty()
                if (latestSegments.isNotEmpty()) {
                    return@withLock analysisResult.copy(
                        analysis = latest,
                        segments = latestSegments,
                        fromCache = true,
                    )
                }
            }
            if (
                mode == SpeechAnalysisMode.AiUnderstanding &&
                analysisResult.fromCache &&
                analysisResult.segments.all { it.userLocked || it.source == SpeechResolutionSource.Ai }
            ) return@withLock analysisResult
            val profiles = knownProfiles(analysisResult.analysis.bookUrl)
            val preset = resolvePreset()
            val refined = try {
                // 朗读轮次被新轮取代时会 cancel 掉整条准备链——如果不隔离，已经发出去的
                // AI 请求（整章分析动辄五六十秒）会被连根掐死、结果全扔，该章下次朗读
                // 又得从零再调一次，表现为「AI 分析总是不成功」。包 NonCancellable 让
                // 这次调用跑完并落库：当前轮回落规则朗读不受影响，补发轮/下次重听直接
                // 命中缓存，不再重复烧 token。书级锁保证它不会与下一轮的分析并发。
                withContext(NonCancellable) {
                    when (mode) {
                        SpeechAnalysisMode.Rule -> analysisResult.segments
                        SpeechAnalysisMode.RuleWithAi -> completeRuleSegments(
                            analysisResult = analysisResult,
                            profiles = profiles,
                            preset = preset,
                            reasoningLevel = reasoningLevel,
                            now = now,
                            source = source,
                        )
                        SpeechAnalysisMode.AiUnderstanding -> {
                            // 整段/整页划分下不能走原子理解：`AiSpeechAtomizer` 会按句末标点把一段重新
                            // 拆成多个片段，让用户显式选择的「一段 = 一个播放单元」失效。此时只让 AI
                            // 补全说话人与情绪，边界仍由规则分段器提供的整单元保持。
                            if (!policy.allowRoleSplits || analysisResult.segments.any(ChapterSpeechSegment::userLocked)) {
                                completeRuleSegments(
                                    analysisResult = analysisResult,
                                    profiles = profiles,
                                    preset = preset,
                                    reasoningLevel = reasoningLevel,
                                    now = now,
                                    source = source,
                                )
                            } else {
                                understandAtoms(
                                    analysisResult = analysisResult,
                                    paragraphs = paragraphs,
                                    profiles = profiles,
                                    preset = preset,
                                    reasoningLevel = reasoningLevel,
                                    now = now,
                                    source = source,
                                )
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                // 用户翻页/停止朗读导致的取消不是失败，不能因此把整章锁进冷却。
                // 但超时必须算失败：`withTimeout` 抛的 TimeoutCancellationException 也是
                // CancellationException 的子类，不单独排除的话超时永远进不了冷却，
                // 每次起播和每一章预合成都会重撞一遍几十秒的超时。
                val cancelledByUser = e is CancellationException && e !is TimeoutCancellationException
                if (!cancelledByUser) {
                    markAiFailed(analysisResult, e, now)
                    bookAiCooldownUntil[bookUrl] = now + AI_FAILURE_COOLDOWN_MS
                }
                throw e
            }
            // 落库同样不随轮次取消中断：结果落了库，补发轮/下次重听才能命中缓存
            withContext(NonCancellable) {
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
                analysisResult.copy(
                    analysis = analysis,
                    segments = refined,
                    fromCache = false,
                )
            }
        }
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
        reasoningLevel: AiReasoningLevel,
        now: Long,
        source: String,
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

        /**
         * 处理一个分块：请求 AI 并把合法决策收进 [updates]。
         *
         * 输出被 max_tokens 硬截断时（finishReason="length"，JSON 断尾必然解析失败），
         * 按 legado_NG 的做法对半拆成两块分别请求——每半的输出随之减半，递归到单段
         * 仍截断才放弃（该段保留规则结果），而不是把整章作废回落规则。
         */
        suspend fun processChunk(chunk: List<ChapterSpeechSegment>) {
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
            val response = try {
                generateResponse(preset, SpeechAnalysisMode.RuleWithAi, payload, reasoningLevel, analysisResult.analysis.chapterIndex, source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.putDebug(
                    "AI 语音复核分块请求失败，已跳过该块（${chunk.size} 段保留规则结果）：${e.message}"
                )
                return
            }
            if (response.finishReason == "length") {
                if (chunk.size < 2) {
                    AppLog.putDebug(
                        "AI 语音复核单段输出仍被 max_tokens 截断，保留规则结果：" +
                            chunk.firstOrNull()?.id.orEmpty()
                    )
                    return
                }
                AppLog.putDebug(
                    "AI 语音复核输出被 max_tokens 截断，块（${chunk.size} 段）对半重试"
                )
                val midpoint = chunk.size / 2
                processChunk(chunk.take(midpoint))
                processChunk(chunk.drop(midpoint))
                return
            }
            val chunkIds = chunk.mapTo(hashSetOf(), ChapterSpeechSegment::id)
            // 单块解析失败（JSON 结构坏、字段非法等）只丢这一块的决策——对应段保留规则
            // 结果，其他块照常生效；不再让一个坏块把整章作废回落规则。
            val chunkDecisions = try {
                parseSegmentDecisions(response.text)
            } catch (e: Throwable) {
                AppLog.putDebug(
                    "AI 语音复核分块解析失败，已跳过该块（${chunk.size} 段保留规则结果）：${e.message}"
                )
                emptyList()
            }
            chunkDecisions.forEach { decision ->
                // 轻量模型跑严格 JSON 契约时可能幻觉 id、重复返回或指向未知角色。
                // 坏决策只丢弃自己（对应段保留规则结果），不再让整章作废回落规则——
                // 整章回落会触发 10 分钟冷却，一次坏分块就废掉整本书的多角色。
                when {
                    decision.segmentId !in chunkIds ->
                        AppLog.putDebug("AI 语音复核返回未知 segmentId，丢弃该条: ${decision.segmentId}")
                    updates.containsKey(decision.segmentId) ->
                        AppLog.putDebug("AI 语音复核重复返回 segmentId，仅保留首条: ${decision.segmentId}")
                    decision.characterId != null && profiles.none { it.id == decision.characterId } ->
                        AppLog.putDebug("AI 语音复核返回未知 characterId，丢弃该条: ${decision.characterId}")
                    else -> updates[decision.segmentId] = decision
                }
            }
            // 不再要求 AI 覆盖全部候选段：漏答的段保留规则结果，并同样标记为已复核，
            // 避免每次重听都为它反复发请求；真正想重跑可在分镜页手动重新分析
        }
        val chunks = candidates.chunkByTextLength(MAX_CHUNK_CHARS) { it.text }
        for (chunk in chunks) {
            processChunk(chunk)
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
            if (decision == null) {
                // AI 漏答：维持规则结果，但标记为已复核，避免重听时为它反复发请求
                return@map segment.copy(source = SpeechResolutionSource.Ai, updatedAt = now)
            }
            val roleType = decision.roleType
            val character = decision.characterId?.let(profilesById::get)
                ?: decision.speakerName?.trim()?.let(profilesByName::get)
            segment.copy(
                roleType = roleType,
                characterId = if (roleType == SpeechRoleType.Narrator) null else character?.id,
                characterName = if (roleType == SpeechRoleType.Narrator) "" else character?.name.orEmpty(),
                emotion = decision.emotion,
                confidence = decision.confidence,
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
            // 单张草稿卡写库失败（如并发唯一索引冲突）只跳过这张卡，不炸整章分析
            runCatching { bookKnowledgeGateway.upsertCharacterProfile(profile) }
                .onSuccess { created += profile }
                .onFailure {
                    AppLog.putDebug("草稿角色卡落库失败，已跳过：$trimmed\n${it.message}")
                }
        }
        return profiles + created
    }

    private suspend fun understandAtoms(
        analysisResult: ChapterSpeechAnalysisResult,
        paragraphs: List<CanonicalSpeechParagraph>,
        profiles: List<BookCharacterProfile>,
        preset: AiTaskPresetConfig,
        reasoningLevel: AiReasoningLevel,
        now: Long,
        source: String,
    ): List<ChapterSpeechSegment> {
        val atoms = paragraphs.flatMap(AiSpeechAtomizer::atomize)
        if (atoms.isEmpty()) return analysisResult.segments
        var knownProfiles = profiles
        val result = mutableListOf<ChapterSpeechSegment>()
        suspend fun processAtomChunk(chunk: List<AiSpeechAtom>) {
            val payload = mapOf(
                "characters" to knownProfiles.map { it.toPromptMap() },
                "atoms" to chunk.map { atom ->
                    mapOf("atomId" to atom.id, "text" to atom.text)
                },
            )
            val response = try {
                generateResponse(preset, SpeechAnalysisMode.AiUnderstanding, payload, reasoningLevel, analysisResult.analysis.chapterIndex, source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.putDebug("AI 理解分块请求失败，该块按未识别朗读（${chunk.size} 原子）：${e.message}")
                return
            }
            // 输出被 max_tokens 硬截断（finishReason=length，JSON 断尾必解析失败）：
            // 按 legado_NG 的做法对半拆成两块分别请求——每半输出随之减半，递归到单原子
            if (response.finishReason == "length") {
                if (chunk.size < 2) {
                    AppLog.putDebug("AI 理解单原子输出仍被 max_tokens 截断，按未识别朗读")
                } else {
                    AppLog.putDebug("AI 理解输出被 max_tokens 截断，块（${chunk.size} 原子）对半重试")
                    val midpoint = chunk.size / 2
                    processAtomChunk(chunk.take(midpoint))
                    processAtomChunk(chunk.drop(midpoint))
                    return
                }
            }
            // 解析失败（结构坏、字段非法）该块按未识别朗读——文本保留、整章不再作废
            val groups = try {
                parseAtomGroups(response.text)
            } catch (e: Throwable) {
                AppLog.putDebug("AI 理解分块解析失败，该块按未识别朗读（${chunk.size} 原子）：${e.message}")
                emptyList()
            }
            val atomsById = chunk.associateBy(AiSpeechAtom::id)
            val knownIds = knownProfiles.mapTo(hashSetOf(), BookCharacterProfile::id)
            // 坏组（未知 characterId、未知 atomId、跨段合并不连续）整组降级为未识别，
            // 组内原子稍后按未识别朗读——文本保留，不再让一个坏组作废整章
            val usableGroups = groups.mapNotNull { group ->
                val groupedAtoms = group.atomIds.mapNotNull { atomsById[it] }
                when {
                    group.characterId != null && group.characterId !in knownIds -> {
                        AppLog.putDebug("AI 理解返回未知 characterId，丢弃该组: ${group.characterId}")
                        null
                    }
                    groupedAtoms.size != group.atomIds.size -> {
                        AppLog.putDebug("AI 理解返回未知 atomId，丢弃该组")
                        null
                    }
                    groupedAtoms.map(AiSpeechAtom::paragraphIndex).distinct().size != 1 -> {
                        AppLog.putDebug("AI 理解试图跨段合并原子，丢弃该组")
                        null
                    }
                    else -> group to groupedAtoms
                }
            }
            knownProfiles = ensureDraftProfiles(
                bookUrl = analysisResult.analysis.bookUrl,
                profiles = knownProfiles,
                speakers = usableGroups.mapNotNull { it.first.newSpeaker() },
                now = now,
            )
            val profilesById = knownProfiles.associateBy(BookCharacterProfile::id)
            val profilesByName = knownProfiles.associateBy { it.name.trim() }
            // 覆盖去重：同一原子只进一个段；未被任何可用组引用的原子按未识别朗读
            val covered = hashSetOf<String>()
            usableGroups.forEach { (group, groupedAtoms) ->
                if (groupedAtoms.any { it.id in covered }) {
                    AppLog.putDebug("AI 理解重复引用原子，丢弃该组")
                    return@forEach
                }
                groupedAtoms.forEach { covered += it.id }
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
            chunk.filter { it.id !in covered }.forEach { atom ->
                val paragraph = paragraphs.first { it.index == atom.paragraphIndex }
                result += ChapterSpeechSegment(
                    id = SpeechIdentity.segmentId(
                        analysisId = analysisResult.analysis.id,
                        paragraphIndex = atom.paragraphIndex,
                        start = atom.start,
                        end = atom.end,
                    ),
                    analysisId = analysisResult.analysis.id,
                    bookUrl = analysisResult.analysis.bookUrl,
                    chapterIndex = analysisResult.analysis.chapterIndex,
                    paragraphIndex = atom.paragraphIndex,
                    start = atom.start,
                    end = atom.end,
                    chapterPosition = paragraph.chapterPosition + atom.start,
                    text = paragraph.text.substring(atom.start, atom.end),
                    roleType = SpeechRoleType.Unknown,
                    characterId = null,
                    characterName = "",
                    emotion = "",
                    confidence = 0.5f,
                    // 标记已复核（结论=未识别）：文本保留、走"未知"音色，避免重听反复发请求
                    source = SpeechResolutionSource.Ai,
                    createdAt = now,
                    updatedAt = now,
                )
            }
        }
        for (chunk in atoms.chunkByTextLength(MAX_CHUNK_CHARS) { it.text }) {
            processAtomChunk(chunk)
        }
        return result.sortedWith(compareBy(ChapterSpeechSegment::paragraphIndex, ChapterSpeechSegment::start))
    }

    private suspend fun generate(
        preset: AiTaskPresetConfig,
        mode: SpeechAnalysisMode,
        payload: Any,
        reasoningLevel: AiReasoningLevel,
        chapterIndex: Int? = null,
        source: String = "朗读",
    ): String = generateResponse(preset, mode, payload, reasoningLevel, chapterIndex, source).text

    private suspend fun generateResponse(
        preset: AiTaskPresetConfig,
        mode: SpeechAnalysisMode,
        payload: Any,
        reasoningLevel: AiReasoningLevel,
        chapterIndex: Int? = null,
        source: String = "朗读",
    ): AiGenerateResponse {
        // 待决策的分段/原子数量决定输出 JSON 的长度：每条决策（segmentId+UUID+枚举）
        // 实测约 200-300 字符。按数量放大 max_tokens，否则长章的决策列表会在
        // max_tokens 处被硬截断，JSON 断尾（EOF at $.segments[N]）解析必炸。
        val decisionCount = when (payload) {
            is Map<*, *> -> (payload["segments"] as? List<*>)?.size
                ?: (payload["atoms"] as? List<*>)?.size
                ?: 0
            else -> 0
        }
        return aiTextGateway.generate(
            AiGenerateRequest(
                model = preset.model,
                messages = listOf(
                    AiMessage(AiMessageRole.SYSTEM, systemPrompt(preset, mode)),
                    AiMessage(AiMessageRole.USER, GSON.toJson(payload)),
                ),
                params = speechAnalysisParams(preset, reasoningLevel, decisionCount),
                taskType = AiTaskType.ANALYZE_SPEECH,
                sourceLabel = chapterIndex?.let { "第 ${it + 1} 章（$source）" },
            )
        ).getOrThrow()
    }

    private suspend fun generateScenes(
        preset: AiTaskPresetConfig,
        chunk: List<ChapterSpeechSegment>,
        reasoningLevel: AiReasoningLevel,
    ): String = aiTextGateway.generate(
        AiGenerateRequest(
            model = preset.model,
            messages = listOf(
                AiMessage(AiMessageRole.SYSTEM, SCENE_PROMPT),
                AiMessage(
                    AiMessageRole.USER,
                    GSON.toJson(
                        mapOf(
                            "segments" to chunk.map { segment ->
                                mapOf(
                                    "segmentId" to segment.id,
                                    "text" to segment.text,
                                    "roleType" to segment.roleType.storageValue,
                                    "speakerName" to segment.characterName.takeIf { it.isNotBlank() },
                                )
                            },
                        )
                    ),
                ),
            ),
            params = speechAnalysisParams(preset, reasoningLevel),
            taskType = AiTaskType.ANALYZE_SPEECH,
        )
    ).getOrThrow().text

    /** 场景拆分结果：扁平后的 segmentIds 必须与输入完全一致（同序、不重不漏），否则视为失败 */
    private fun parseScenes(text: String, expectedIds: List<String>): List<ParsedScene> {
        val array = parseRoot(text).getAsJsonArray("scenes")
        val scenes = array.map { element ->
            val item = element.asJsonObject
            ParsedScene(
                title = item.optionalString("title").orEmpty()
                    .replace(Regex("\\s+"), " ").trim().take(MAX_SCENE_TITLE_CHARS),
                segmentIds = item.getAsJsonArray("segmentIds").map { it.asString },
            )
        }
        require(scenes.isNotEmpty()) { "AI returned no scenes" }
        require(scenes.flatMap(ParsedScene::segmentIds) == expectedIds) {
            "AI scene coverage is incomplete, duplicated, or out of order"
        }
        return scenes
    }

    private data class ParsedScene(val title: String, val segmentIds: List<String>)

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

        /**
         * 分块按输入文本长度切，块数决定章的 AI 请求数（3000 字章 1 块、1 万字章 2 块）。
         * 输出预算已按分段数放大（见 [speechAnalysisParams]，每段 250 tokens 余量），
         * 6000 字块（约 30 段）的输出也远在预算内；万一仍被 max_tokens 截断，
         * 块处理会按 finishReason="length" 对半拆分重试，单块不拖垮整章。
         */
        private const val MAX_CHUNK_CHARS = 6_000

        private const val HYBRID_CONFIDENCE_THRESHOLD = 0.75f
        private const val DRAFT_SPEAKER_CONFIDENCE = 0.6f

        /** AI 失败后多久之内不再重试，10 分钟够覆盖「反复点段落起读」这种连续操作 */
        private const val AI_FAILURE_COOLDOWN_MS = 10 * 60 * 1000L

        /** 失败原因只用于排障提示，截断避免把整段响应写进库 */
        private const val MAX_ERROR_LENGTH = 200

        /** 场景短标题的展示上限，和说话人名一致由 UI 单行截断兜底 */
        private const val MAX_SCENE_TITLE_CHARS = 24

        /**
         * 场景拆分提示词。刻意不并入 [systemPrompt]：promptHash 是分析缓存身份的一部分，
         * 场景词改动不该让全书说话人分析重来。
         */
        private const val SCENE_PROMPT =
            "Split the consecutive speech segments of one fiction chapter into a few storyboard " +
                "scenes for TTS review. Cut only at real scene changes (place, time, cast or " +
                "topic). Scenes must be contiguous, cover every segment exactly once in input " +
                "order, and never be empty. Give each scene a short title in the language of the " +
                "text. Return only one JSON object: " +
                "{\"scenes\":[{\"title\":string,\"segmentIds\":[string]}]}. " +
                "Never rewrite text and never invent IDs."
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

/**
 * Request params for AI speech analysis.
 *
 * The caller's level wins because the preset/model default (MEDIUM) would otherwise force thinking
 * on for every analysis: models that think by default (Zhipu GLM, DeepSeek) then spend the answer on
 * `reasoning_content` and the strict JSON contract of this task fails. AUTO keeps the presets in
 * charge, mirroring [io.legado.app.domain.usecase.IdentifyBookCharactersUseCase.identifyStream].
 *
 * [decisionCount]（待决策的分段/原子数）决定输出预算：决策 JSON 逐段枚举，分段多的章
 * 输出远超固定下限，预算不足时模型在 max_tokens 处硬截断，JSON 断尾解析必炸。
 */
internal fun speechAnalysisParams(
    preset: AiTaskPresetConfig,
    reasoningLevel: AiReasoningLevel,
    decisionCount: Int = 0,
): AiGenerationParams = preset.params.copy(
    temperature = 0f,
    reasoningLevel = reasoningLevel
        .takeUnless { it == AiReasoningLevel.AUTO }
        ?: preset.params.reasoningLevel,
    maxOutputTokens = maxOf(
        preset.params.maxOutputTokens ?: 0,
        MIN_OUTPUT_TOKENS,
        // 每条决策 JSON（hex segmentId + UUID + 枚举）按 250 tokens 留足余量
        decisionCount * 250 + 2_000,
    ).coerceAtMost(30_000),
)

private const val MIN_OUTPUT_TOKENS = 8_000
