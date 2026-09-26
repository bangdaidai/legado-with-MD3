package io.legado.app.domain.usecase

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
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
import io.legado.app.domain.model.readaloud.SPEAKER_DIALOGUE_FEMALE_NAME
import io.legado.app.domain.model.readaloud.SPEAKER_DIALOGUE_MALE_NAME
import io.legado.app.domain.model.readaloud.dialogueFallbackName
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
                // 先做对白兜底性别补全（路人泛称编码成虚拟说话人 + 邻接称呼传播），
                // 再合并相邻同说话人片段（合并依赖虚拟名一致）——原文（书源）普遍按
                // “一句对话一个段落”切，合并后听书页与分镜页按“说话人连续台词”呈现，
                // 朗读停顿也随之减少
                val refined = mergeSameSpeakerSegments(withDialogueGenderFallback(refined))
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

    /**
     * 邻接称呼性别传播（对齐 legado_NG 的 applyAdjacentGenderEvidence）。
     *
     * 泛称段的性别编码（characterName =「对白男/对白女」）在 [completeRuleSegments] /
     * [understandAtoms] 生成段时已完成；这里补第二种证据：前一段台词以性别称呼开头
     * （「小妹妹你以后会……」）而当前段完全未知时，称呼指的就是当前说话人——
     * 把它的性别传播给当前段。只补 characterId、characterName 均空的段，
     * 已有角色归属或已带虚拟名的段落不动。
     */
    private fun withDialogueGenderFallback(
        segments: List<ChapterSpeechSegment>,
    ): List<ChapterSpeechSegment> {
        if (segments.size < 2) return segments
        val result = segments.toMutableList()
        var propagated = 0
        for (index in 1 until result.size) {
            val previous = result[index - 1]
            val current = result[index]
            if (previous.roleType !in SPOKEN_ROLE_TYPES ||
                current.roleType !in SPOKEN_ROLE_TYPES
            ) continue
            // 只补完全未知的段：已有角色归属或已带虚拟名的段落不动
            if (current.characterId != null || current.characterName.isNotBlank()) continue
            if (current.paragraphIndex - previous.paragraphIndex !in 0..1) continue
            // 前一段台词以性别称呼开头（「小妹妹你以后会……」的下一段回答就是「小妹妹」）
            // 时，称呼指的就是当前说话人——NG 同款邻接性别证据
            val cue = previous.text.trimStart { it.isWhitespace() || it in "“”‘’\"'" }
            val address = (FEMALE_ADDRESSES + MALE_ADDRESSES).firstOrNull(cue::startsWith)
                ?: continue
            val gender = if (address in FEMALE_ADDRESSES) "female" else "male"
            val speaker = dialogueFallbackName(gender).orEmpty()
            result[index] = current.copy(characterName = speaker)
            propagated++
            AppLog.putDebug(
                "对白兜底：前段称呼“$address”把第 ${current.paragraphIndex + 1} 段归为「$speaker」"
            )
        }
        if (propagated > 0) {
            AppLog.putDebug("对白兜底：本章邻接称呼传播 $propagated 段")
        }
        return result
    }

    /**
     * 相邻同说话人的片段合并：原文（书源）普遍按“一句对话一个段落”切，逐段出卡片
     * 会把一段连续台词拆成七八张。只合并章内无缝且说话人明确的相邻段——
     * 两个都未绑定但 AI 给了同一稳定称呼（含「对白男/对白女」虚拟说话人）的段落
     * 也合并（NG 同款：规范名相同即同一说话人）；
     * 用户手动锁定的段不动。
     */
    private fun mergeSameSpeakerSegments(
        segments: List<ChapterSpeechSegment>,
    ): List<ChapterSpeechSegment> {
        if (segments.size < 2) return segments
        val result = mutableListOf<ChapterSpeechSegment>()
        var merged = 0
        segments.forEach { segment ->
            val previous = result.lastOrNull()
            val seamless =
                previous != null && previous.chapterPosition + previous.text.length == segment.chapterPosition
            val sameNarrator = previous?.roleType == SpeechRoleType.Narrator &&
                segment.roleType == SpeechRoleType.Narrator
            // 纯标点/空白碎段（跨段引号的闭合引号等）无条件并入前段：它没有朗读
            // 内容、说话人也无从谈起，并进前段只为文本完整——分镜与听书页不再出现
            // 只有引号的卡片
            val isPunctuationFragment = segment.text.matches(AppPattern.notReadAloudRegex)
            val sameCharacter = segment.characterId != null &&
                segment.characterId == previous?.characterId &&
                previous.roleType != SpeechRoleType.Narrator
            val sameNamedSpeaker = segment.characterId == null &&
                previous?.characterId == null &&
                segment.characterName.isNotBlank() &&
                segment.characterName == previous.characterName &&
                previous.roleType != SpeechRoleType.Narrator
            if (
                previous != null &&
                seamless && (isPunctuationFragment || sameNarrator || sameCharacter || sameNamedSpeaker) &&
                !previous.userLocked && !segment.userLocked
            ) {
                merged++
                result[result.lastIndex] = previous.copy(
                    end = segment.end,
                    text = previous.text + segment.text,
                    emotion = pickSpokenEmotion(previous.emotion, segment.emotion),
                    confidence = minOf(previous.confidence, segment.confidence),
                    updatedAt = segment.updatedAt,
                )
            } else {
                result += segment
            }
        }
        if (merged > 0) {
            AppLog.putDebug("同说话人合并：本章 $merged 处（${segments.size} 段 → ${result.size} 段）")
        }
        return result
    }

    /** 合并相邻片段时挑情绪：有明确情绪（非空非 neutral）优先 */
    private fun pickSpokenEmotion(first: String, second: String): String =
        listOf(first, second).firstOrNull { it.isNotBlank() && it != SpeechEmotion.Neutral.storageValue }
            ?: first.ifBlank { second }

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
            // AI 确认是人物声音但没归属到任何角色（路人泛称）时，把性别编码成虚拟
            // 说话人名（「对白男/对白女」）：音色计划据此走对白兜底绑定，不建角色卡
            val fallbackName = if (character == null) {
                dialogueFallbackName(decision.speakerGender)
            } else {
                null
            }
            if (fallbackName != null) {
                AppLog.putDebug(
                    "对白兜底：「${decision.speakerGender}」泛称说话人记为「$fallbackName」" +
                        "（${segment.text.take(12)}…）"
                )
            }
            segment.copy(
                roleType = roleType,
                characterId = if (roleType == SpeechRoleType.Narrator) null else character?.id,
                characterName = when {
                    roleType == SpeechRoleType.Narrator -> ""
                    character != null -> character.name
                    else -> fallbackName.orEmpty()
                },
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
            val paragraphsById = paragraphs.associateBy(CanonicalSpeechParagraph::index)
            val payload = mapOf(
                "characters" to knownProfiles.map { it.toPromptMap() },
                "atoms" to chunk.map { atom ->
                    val paragraphText = paragraphsById[atom.paragraphIndex]?.text.orEmpty()
                    mapOf(
                        "atomId" to atom.id,
                        "text" to atom.text,
                        // 原子所在段内的前后文：说话引导句常紧贴引号
                        "contextBefore" to paragraphText.substring(
                            (atom.start - 48).coerceAtLeast(0),
                            atom.start,
                        ),
                        "contextAfter" to paragraphText.substring(
                            atom.end,
                            (atom.end + 48).coerceAtMost(paragraphText.length),
                        ),
                    )
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
                // AI 确认是人物声音但没归属到任何角色（路人泛称）时，把性别编码成
                // 虚拟说话人名（「对白男/对白女」）：音色计划据此走对白兜底绑定
                val fallbackName = if (character == null) {
                    dialogueFallbackName(group.speakerGender)
                } else {
                    null
                }
                if (fallbackName != null) {
                    AppLog.putDebug(
                        "对白兜底：「${group.speakerGender}」泛称说话人记为「$fallbackName」"
                    )
                }
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
                    characterName = when {
                        group.roleType == SpeechRoleType.Narrator -> ""
                        character != null -> character.name
                        else -> fallbackName.orEmpty()
                    },
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
            // 主体（角色设定 + 归因规则）：默认来自 NG 对齐版，用户在提示词预设里
            // 保存过则整段覆盖；输出 JSON 契约必须与解析器严格一致，永远由代码追加
            append(custom ?: DEFAULT_PROMPT)
            if (mode == SpeechAnalysisMode.RuleWithAi) {
                append(SEGMENT_SCHEMA_RULES)
            } else {
                append(ATOM_SCHEMA_RULES)
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
        optionalString("speakerName")?.trim()?.takeIf {
            it.isNotBlank() && it.length <= 24 && it !in VIRTUAL_SPEAKER_NAMES
        }

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
        /**
         * v3：提示词对齐 legado_NG（中文归因规则 + 泛称对白兜底），并新增虚拟说话人
         * 编码与同名合并——旧分析缓存的结论与新版后处理不一致，整体失效重析。
         */
        const val VERSION = "ai-speech-analysis-v3"

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

        /**
         * 归因提示词主体，对齐 legado_NG 的 base-routing/protocol 提示词（中文、面向中文网文）。
         * 提示词预设里保存的自定义内容会整段覆盖它；JSON 输出契约仍由 [SEGMENT_SCHEMA_RULES]
         * / [ATOM_SCHEMA_RULES] 代码追加。strings.xml 的 ai_prompt_default_analyze_speech
         * 是同一份文本（提示词配置页的默认值/重置目标），改这里必须同步改它。
         */
        internal const val DEFAULT_PROMPT =
            "你是中文网文有声书的分镜师。结合章节上下文，判断输入分段属于旁白、人物对白、" +
                "人物心声还是其它内容，并确认说话人。客户端按你的结论为每段路由音色，" +
                "你只做归因，不改写正文，也不返回正文。\n" +
                "归因规则：\n" +
                "1. 人物真正说出口的话用 character；人物脑内直接想法用 thought，" +
                "心声主人由「某人心想、暗道、心里想」等提示语的主语确定。\n" +
                "2. 动作、叙述、环境、标题、日期、书信、黑板文字、拟声词用 narrator。" +
                "引号不等于人物声音：嵌在完整叙述句里的回想、复述、概括或对某句话的指称用 narrator，" +
                "例如“那句‘靠你了’总往她心窝子钻”。只有人物此刻真正开口、独立呈现的原话才用 character。\n" +
                "3. 说话人以发言动词、动作承接、声音说明、上下文主语和连续对话关系为依据。" +
                "被提到、被称呼、被看见或被想到的人不等于说话人。\n" +
                "4. 先匹配 characters 里的 characterId。网名、昵称、账号名、群名片、代号、乳名和" +
                "外号首先是已有人物的身份标签：正文出现「X 是 Y 的网名/昵称」或「哦，是 Y」等" +
                "明确映射时，必须复用 Y 的 characterId，不得为 X 另立说话人。\n" +
                "5. 说话人是明确的人但没命中任何已有角色时：characterId 留空，把稳定称呼" +
                "（姓名、外号、唯一称谓）写进 speakerName，客户端会为它建临时角色。" +
                "即使这个人物只出现一场，只要有稳定称呼也要写；不确定就留空，不要编造。\n" +
                "6. 「大汉」「侍卫」「老捕头」「下属」这类一次性职业、群体或外貌泛称是路人：" +
                "characterId 与 speakerName 都留空。但路人只要是人物声音，性别能确认就写 " +
                "male/female，客户端会用对白兜底音色发声，不要因为身份未知就把对白吞成旁白。\n" +
                "7. speakerGender 只在正文有依据时返回 male/female：明确性别代词（她/他）、" +
                "性别称呼（小姐、公子、夫人、姑娘、大哥、小妹妹）、「我叫……」类自述都是依据；" +
                "姓名和职业本身不是证据。输出前复核每个 male/female 能否引用到正文线索，" +
                "找不到就写 unknown，不要为了选音瞎猜；也尽量把称呼、代词提供的线索找尽，" +
                "不要轻易留 unknown。\n" +
                "8. 无法确认是人物声音时才按 narrator 处理；不得因为说话人身份或性别未知" +
                "而吞掉已确认的对白。\n" +
                "9. emotion 只在文本有明确语气线索时给，不确定写 neutral；confidence 如实反映" +
                "证据强度，高置信度永远不能代替证据。"

        /** 分段复核模式的 JSON 输出契约，必须与 [parseSegmentDecisions] 的解析严格一致 */
        private const val SEGMENT_SCHEMA_RULES =
            "\n只返回一个 JSON 对象，不要 Markdown 与解释，不要发明 ID：" +
                "\n{\"segments\":[{\"segmentId\":\"输入中的 ID\",\"roleType\":" +
                "\"narrator|character|thought|unknown\",\"characterId\":\"characters 中的 ID 或 null\"," +
                "\"speakerName\":\"稳定称呼或 null\",\"speakerGender\":\"male|female|unknown\"," +
                "\"emotion\":\"neutral|cheerful|sad|angry|fearful|surprised|disgusted|whispering|calm\"," +
                "\"confidence\":0.88}]}。" +
                "每个输入分段必须返回且只返回一条决策。"

        /** 原子理解模式的 JSON 输出契约，必须与 [parseAtomGroups] 的解析严格一致 */
        private const val ATOM_SCHEMA_RULES =
            "\n只返回一个 JSON 对象，不要 Markdown 与解释，不要发明 ID：" +
                "\n{\"segments\":[{\"atomIds\":[\"输入中的原子 ID\"],\"roleType\":" +
                "\"narrator|character|thought|unknown\",\"characterId\":\"characters 中的 ID 或 null\"," +
                "\"speakerName\":\"稳定称呼或 null\",\"speakerGender\":\"male|female|unknown\"," +
                "\"emotion\":\"neutral|cheerful|sad|angry|fearful|surprised|disgusted|whispering|calm\"," +
                "\"confidence\":0.88}]}。" +
                "每个原子必须恰好归入一组、按输入顺序分组；一组内的原子必须连续且同属一段，" +
                "同一段里同一说话人的连续原子要并入同一组，不要拆碎。"

        /** 会开口的角色类型（对白与心声），性别兜底/传播只作用于它们 */
        private val SPOKEN_ROLE_TYPES = setOf(SpeechRoleType.Character, SpeechRoleType.Thought)

        /** 虚拟说话人名不属于稳定称呼，AI 直接返回时也绝不据此建卡 */
        private val VIRTUAL_SPEAKER_NAMES = setOf(
            SPEAKER_DIALOGUE_MALE_NAME,
            SPEAKER_DIALOGUE_FEMALE_NAME,
        )

        /** 邻接称呼传播的女性称呼（NG 同款表） */
        private val FEMALE_ADDRESSES = listOf(
            "小妹妹", "妹妹", "小姑娘", "姑娘", "小姐", "女士", "女侠", "夫人", "娘子",
        )

        /** 邻接称呼传播的男性称呼（NG 同款表） */
        private val MALE_ADDRESSES = listOf(
            "小弟弟", "弟弟", "小公子", "公子", "少爷", "先生", "小哥", "大哥", "大叔", "老爷",
        )
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
