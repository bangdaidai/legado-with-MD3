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
import io.legado.app.domain.model.readaloud.SpeechAttributionStrictness
import io.legado.app.domain.model.readaloud.SpeechEmotion
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.domain.model.readaloud.SPEAKER_DIALOGUE_FEMALE_NAME
import io.legado.app.domain.model.readaloud.SPEAKER_DIALOGUE_MALE_NAME
import io.legado.app.domain.model.readaloud.dialogueFallbackName
import io.legado.app.help.readaloud.cast.VoicePoolStore
import io.legado.app.help.readaloud.segment.AiSpeechAtom
import io.legado.app.help.readaloud.segment.AiSpeechAtomizer
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
     * 冷却跳过提示的去重：bookUrl → 已经提示过的冷却截止时间。
     *
     * 冷却是上一轮 AI 失败留下的，期间每一章都只会按规则读。原本一声不吭，才有
     * 「明明配了模型，整本听下来全是旁白，还以为是模型没生效」。同一轮冷却只弹一次，
     * 后续章节只进日志；下次失败会写入更晚的截止时间，提示随之重新弹一次。
     */
    private val cooldownNoticeUntil = ConcurrentHashMap<String, Long>()

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

    /**
     * 单飞表（学 legado_NG 的 inFlightRequests）：分析身份键 → 在飞轮次的结果承诺。
     *
     * 朗读轮和分镜轮前后脚到达同一章时，后来者不再"锁上排队 → 进锁重查缓存"，
     * 直接挂在在飞轮的结果上共享——两边输入口径一致（同 contentHash/模型/prompt），
     * 产出的就是同一份分析。键含 contentHash+resolverVersion（即 analysis.id），
     * 文本或模式变了自动各走各的，不会错拿别人的结果。在飞轮失败/被取消时
     * 承诺以异常收尾，等待方自己补跑（锁与冷却兜底）。
     */
    private val inFlightAnalyses =
        ConcurrentHashMap<String, CompletableDeferred<ChapterSpeechAnalysisResult>>()

    fun clearAiCooldown(bookUrl: String) {
        bookAiCooldownUntil.remove(bookUrl)
        cooldownNoticeUntil.remove(bookUrl)
    }

    /**
     * 冷却期内跳过 AI：这一章只拿到规则结论，必须说清楚"不是模型没配，是上次失败后在冷却"。
     *
     * 沉默的回落是「配了模型却全程单角色」最难查的一段——日志里连一行都没有。
     */
    private fun notifyCooldownSkip(
        bookUrl: String,
        chapterIndex: Int,
        cooldownUntil: Long,
        now: Long,
    ) {
        val remainingSec = ((cooldownUntil - now + 999) / 1000).coerceAtLeast(1L)
        val isNewCooldown = cooldownNoticeUntil[bookUrl] != cooldownUntil
        if (isNewCooldown) {
            cooldownNoticeUntil[bookUrl] = cooldownUntil
        }
        AppLog.put(
            "AI 分配角色: 第${chapterIndex + 1}章跳过 AI，按规则读——" +
                "本书上一次 AI 识别失败，冷却还剩 ${remainingSec} 秒",
            toast = isNewCooldown,
        )
    }

    /**
     * 给缺少场景分组的分段补上场景：按章分段（含文本顺序）交给 AI 切分成连续的场景组。
     *
     * 唯一的发起方是分镜页 withScenes：点开某章、发现该章存在无场景分段时按需补一次
     * （source=分镜）。说话人分析主链不产出场景——场景只对分镜页的阅读呈现有用，对朗读
     * 本身零消费，不值得占用起播/预合成那一轮。失败原样返回、不写冷却：场景是锦上添花层，
     * 拆坏了既不能拖累功能，调用方按「无场景」降级展示，下次进页再补。
     */
    suspend fun assignScenes(
        segments: List<ChapterSpeechSegment>,
        reasoningLevel: AiReasoningLevel = AiReasoningLevel.OFF,
        source: String = "分镜",
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
                    text = generateScenes(preset, chunk, reasoningLevel, source),
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

    /**
     * 分析缓存版本号 = **解析口径**：切分规则 / 分析模式 / 模型 / 提示词 / 归因口径。
     *
     * 角色库不在这里。它曾经是本串的一段 `md5(所有非草稿档案的 id:updatedAt)`，代价是
     * 名册里任意一行时间戳一变（转正、改别名、换池，甚至详情页只动头像——保存是整行重建）
     * 就让全书每一章的 analysisId 对不上，缓存整体失效、重烧一遍 token。
     * 但"谁说了这句"是历史事实：名册变了不改变那句话当时判给谁。
     * 名册对分析的真正影响只有"后续新章要用新名册"，而每次分析都现读档案
     * （见 [analyzeChapter] 里的 `knownProfiles`），这条本来就自动成立。
     * 想让旧章按新名册重判，用分镜页的「重新分析」显式动作。
     *
     * 归因口径 [strictness] 在键里：它换的是发给模型的那段口径文本，同一章在两档下
     * 的结论本来就该不同，共用一个键会让切换后拿到另一档的旧结果。
     *
     * 归零：把该段从串里去掉即恢复旧行为，旧分析行没有删改，键算回去就能命中。
     */
    suspend fun resolverVersion(
        mode: SpeechAnalysisMode,
        policy: ContentSplitPolicy,
        strictness: SpeechAttributionStrictness = SpeechAttributionStrictness.MustAnswer,
    ): String {
        if (mode == SpeechAnalysisMode.Rule) return ruleResolverVersion(policy)
        val preset = resolvePreset()
        val promptHash = MD5Utils.md5Encode(systemPrompt(preset, mode, strictness))
        return listOf(
            // 与纯规则分段共用同一前缀，AI 结果才不会被误判成规则模式
            ruleResolverVersion(policy),
            VERSION,
            mode.storageValue,
            strictness.storageValue,
            preset.model.id,
            promptHash,
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
        /** 归因口径：拿不准时硬给一个人（必答）还是留空走默认音（宁缺毋滥）。 */
        strictness: SpeechAttributionStrictness = SpeechAttributionStrictness.MustAnswer,
    ): ChapterSpeechAnalysisResult {
        if (mode == SpeechAnalysisMode.Rule) return analysisResult
        // 单飞：键就是 analysis.id（书+章+contentHash+resolverVersion 的确定性身份），
        // 文本、模型或模式变了自动各飞各的，等待方不会错拿到"不是自己要的那份"结果。
        val key = analysisResult.analysis.id
        val mine = CompletableDeferred<ChapterSpeechAnalysisResult>()
        val running = inFlightAnalyses.putIfAbsent(key, mine)
        if (running == null) {
            try {
                val result = analyzeExclusive(
                    analysisResult, paragraphs, mode, reasoningLevel, policy, now, source, strictness,
                )
                mine.complete(result)
                return result
            } catch (e: Throwable) {
                mine.completeExceptionally(e)
                throw e
            } finally {
                inFlightAnalyses.remove(key, mine)
            }
        }
        val shared: ChapterSpeechAnalysisResult? = try {
            running.await()
        } catch (e: CancellationException) {
            // 取消有两种：来自自己（照常传播），来自在飞那一轮（它没跑完，
            // 这一份没有落库结果可共享）——本轮自己补跑，锁与冷却兜底
            currentCoroutineContext().ensureActive()
            AppLog.putDebug(
                "单飞：同键在飞分析提前结束，本轮自行执行：" +
                    "${analysisResult.analysis.bookUrl} 第${analysisResult.analysis.chapterIndex + 1}章"
            )
            null
        } catch (e: Throwable) {
            null
        }
        if (shared != null) return shared
        return analyzeExclusive(
            analysisResult, paragraphs, mode, reasoningLevel, policy, now, source, strictness,
        )
    }

    private suspend fun analyzeExclusive(
        analysisResult: ChapterSpeechAnalysisResult,
        paragraphs: List<CanonicalSpeechParagraph>,
        mode: SpeechAnalysisMode,
        reasoningLevel: AiReasoningLevel,
        policy: ContentSplitPolicy,
        now: Long,
        source: String,
        strictness: SpeechAttributionStrictness,
    ): ChapterSpeechAnalysisResult {
        val bookUrl = analysisResult.analysis.bookUrl
        // 同一本书串行化：冷却判断、AI 调用、冷却写入都在锁内，
        // 避免朗读与分镜页并发各自发一次 AI（竞态下冷却还没写入就都通过了检查）。
        // 锁按"书+章"分粒度：全书一把锁会让预合成的第 8 章分析阻塞第 7 章的朗读分析
        // （用户实测等 72 秒）；同章仍互斥（防朗读与分镜页并发重复分析）
        val guard = bookLocks.getOrPut("$bookUrl#${analysisResult.analysis.chapterIndex}") { Mutex() }
        return guard.withLock {
            val cooldownUntil = bookAiCooldownUntil[bookUrl]
            if (cooldownUntil != null && now < cooldownUntil) {
                notifyCooldownSkip(bookUrl, analysisResult.analysis.chapterIndex, cooldownUntil, now)
                return@withLock analysisResult
            }
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
            // 声音池候选整章取一次：新建说话人要按性别年龄气质挑池（与逐句 AI 分配同一份候选）
            val pools = runCatching { VoicePoolStore.enabledPoolNames() }.getOrDefault(emptyList())
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
                            strictness = strictness,
                            pools = pools,
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
                                    strictness = strictness,
                                    pools = pools,
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
                                    strictness = strictness,
                                    pools = pools,
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
                cooldownNoticeUntil.remove(bookUrl)
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
            // previous != null 放首位做智能转换的前提：previous?.characterId == null
            // 无法排除 previous 本身为 null，编译器不会把后续的 previous 当非空
            val sameNamedSpeaker = previous != null &&
                segment.characterId == null &&
                previous.characterId == null &&
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
        strictness: SpeechAttributionStrictness,
        pools: List<String>,
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
            // 池显示名单独解析一份：voiceAgeBand 现在是枚举，AI 提示词里要的是池名
            val poolLabels = profiles.associate { it.id to VoicePoolStore.poolLabelOf(it) }
            val payload = mapOf(
                "characters" to profiles.map {
                    it.toPromptMap(poolLabels[it.id].orEmpty())
                },
                // 新建说话人从这里挑声音池；已有角色带着自己的池进来，模型不该再给
                "pools" to pools,
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
                generateResponse(
                    preset = preset,
                    mode = SpeechAnalysisMode.RuleWithAi,
                    payload = payload,
                    reasoningLevel = reasoningLevel,
                    chapterIndex = analysisResult.analysis.chapterIndex,
                    source = source,
                    strictness = strictness,
                )
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

    /** AI 认出的人物声音，但没命中已有角色卡时，返回待建草稿卡的称呼、性别与 AI 建议池。 */
    private fun AiSegmentDecision.newSpeaker(): SpeakerSuggestion? = suggestion(
        characterId = characterId,
        roleType = roleType,
        name = speakerName,
        gender = speakerGender,
        poolLabel = speakerPool,
    )

    private fun AiAtomGroup.newSpeaker(): SpeakerSuggestion? = suggestion(
        characterId = characterId,
        roleType = roleType,
        name = speakerName,
        gender = speakerGender,
        poolLabel = speakerPool,
    )

    /** 只有没命中已有角色、又确实是人物声音的条目才建卡（泛称走对白兜底，不建卡）。 */
    private fun suggestion(
        characterId: String?,
        roleType: SpeechRoleType,
        name: String?,
        gender: String,
        poolLabel: String,
    ): SpeakerSuggestion? = when {
        characterId != null -> null
        roleType != SpeechRoleType.Character && roleType != SpeechRoleType.Thought -> null
        else -> name?.let { SpeakerSuggestion(it, gender, poolLabel) }
    }

    /**
     * 把 AI 认出但还没进角色卡的说话人落成草稿角色卡（临时说话人）。
     *
     * 草稿卡在配音页单独一栏、可以直接绑音色、在朗读里参与路由，但只有用户点「转正」才会变成正式角色卡。
     * 按名字去重，因为 `book_character_profiles` 对 (bookUrl, name) 有唯一索引。
     *
     * AI 建议的声音池只作用在**新建**这一张卡上：已有档案的池一律不动，改池等于换声音。
     * 池名查无此池（模型自创、或池被删）时留空，交给自动选音按性别兜底。
     */
    private suspend fun ensureDraftProfiles(
        bookUrl: String,
        profiles: List<BookCharacterProfile>,
        speakers: List<SpeakerSuggestion>,
        now: Long,
    ): List<BookCharacterProfile> {
        if (speakers.isEmpty()) return profiles
        val existingNames = profiles.mapTo(hashSetOf()) { it.name.trim() }
        val created = mutableListOf<BookCharacterProfile>()
        speakers.distinctBy { it.name.trim() }.forEach { speaker ->
            val trimmed = speaker.name.trim()
            if (trimmed.isBlank() || !existingNames.add(trimmed)) return@forEach
            val pool = speaker.poolLabel.trim()
                .takeIf { it.isNotEmpty() }
                ?.let { label -> runCatching { VoicePoolStore.poolByName(label) }.getOrNull() }
            if (speaker.poolLabel.isNotBlank() && pool == null) {
                AppLog.putDebug("AI 建议的声音池「${speaker.poolLabel}」不存在，新人「$trimmed」先不指定池")
            }
            val profile = BookCharacterProfile(
                id = Uuid.random().toString(),
                bookUrl = bookUrl,
                name = trimmed,
                voiceGender = speaker.gender,
                voicePoolId = pool?.id.orEmpty(),
                voiceAgeBand = pool?.ageBand ?: BookCharacterProfile.VOICE_AGE_UNKNOWN,
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
        strictness: SpeechAttributionStrictness,
        pools: List<String>,
    ): List<ChapterSpeechSegment> {
        val atoms = paragraphs.flatMap(AiSpeechAtomizer::atomize)
        if (atoms.isEmpty()) return analysisResult.segments
        var knownProfiles = profiles
        val result = mutableListOf<ChapterSpeechSegment>()
        suspend fun processAtomChunk(chunk: List<AiSpeechAtom>) {
            val paragraphsById = paragraphs.associateBy(CanonicalSpeechParagraph::index)
            // 池显示名单独解析一份：voiceAgeBand 现在是枚举，AI 提示词里要的是池名
            val poolLabels = knownProfiles.associate { it.id to VoicePoolStore.poolLabelOf(it) }
            val payload = mapOf(
                "characters" to knownProfiles.map {
                    it.toPromptMap(poolLabels[it.id].orEmpty())
                },
                // 新建说话人从这里挑声音池；已有角色带着自己的池进来，模型不该再给
                "pools" to pools,
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
                generateResponse(
                    preset = preset,
                    mode = SpeechAnalysisMode.AiUnderstanding,
                    payload = payload,
                    reasoningLevel = reasoningLevel,
                    chapterIndex = analysisResult.analysis.chapterIndex,
                    source = source,
                    strictness = strictness,
                )
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

    private suspend fun generateResponse(
        preset: AiTaskPresetConfig,
        mode: SpeechAnalysisMode,
        payload: Any,
        reasoningLevel: AiReasoningLevel,
        chapterIndex: Int? = null,
        source: String = "朗读",
        strictness: SpeechAttributionStrictness = SpeechAttributionStrictness.MustAnswer,
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
                    AiMessage(AiMessageRole.SYSTEM, systemPrompt(preset, mode, strictness)),
                    AiMessage(AiMessageRole.USER, GSON.toJson(payload)),
                ),
                params = speechAnalysisParams(preset, reasoningLevel, decisionCount),
                taskType = AiTaskType.ANALYZE_SPEECH,
                // 章号 + 发起链 + 这次在干什么，全部进日志场景行，不让人猜
                sourceLabel = chapterIndex?.let { "第 ${it + 1} 章（${source}·说话人分析）" },
            )
        ).getOrThrow()
    }

    private suspend fun generateScenes(
        preset: AiTaskPresetConfig,
        chunk: List<ChapterSpeechSegment>,
        reasoningLevel: AiReasoningLevel,
        source: String,
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
            // 场景拆分此前是语音润色里唯一不带章号的一档，取消记录没法定位到章；
            // 章号取块内分段自带的（同一次 assignScenes 只处理同一章）。
            // 发起链只有分镜页按需补这一路（主链不产出场景，见 assignScenes 注释）
            sourceLabel = "第 ${chunk.first().chapterIndex + 1} 章（${source}·场景拆分）",
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

    /**
     * 发给模型的完整 system prompt = 归因规则主体 + **归因口径** + 输出契约。
     *
     * 用户在提示词预设里保存过内容时，只替换主体；口径与契约照样追加——口径是朗读设置里
     * 那一档单选（必答 / 宁缺毋滥），契约必须与解析器严格一致，都不允许被预设改掉。
     */
    private fun systemPrompt(
        preset: AiTaskPresetConfig,
        mode: SpeechAnalysisMode,
        strictness: SpeechAttributionStrictness,
    ): String {
        val custom = preset.promptTemplate.takeIf {
            preset.taskType == AiTaskType.ANALYZE_SPEECH && it.isNotBlank()
        }
        return buildString {
            // 主体（角色设定 + 归因规则）：默认来自 NG 对齐版，用户在提示词预设里
            // 保存过则整段覆盖；输出 JSON 契约必须与解析器严格一致，永远由代码追加
            append(custom ?: DEFAULT_PROMPT)
            append("\n")
            append(
                when (strictness) {
                    SpeechAttributionStrictness.MustAnswer -> ATTRIBUTION_MUST_ANSWER
                    SpeechAttributionStrictness.EvidenceOnly -> ATTRIBUTION_EVIDENCE_ONLY
                }
            )
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
                speakerPool = item.speakerPool(),
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
                speakerPool = item.speakerPool(),
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

    /**
     * 正文给出的稳定称呼；泛称（大汉、老捕头之类）一律当路人，不建卡。
     *
     * 「我/他/旁白」这类占位主名在这里挡第二道：契约里已经明令禁止（DEFAULT_PROMPT 第 6 条），
     * 但这类名字一旦建档就再也删不干净——它是旧「AI 分配角色」引擎实证的"分错了还越改越乱"
     * 放大器（任何没依据的句子都能往「我」身上安），所以模型返回了也不落库。
     * 挡下之后这条决策仍然生效，只是不建卡：那句走对白兜底或默认音。
     */
    private fun JsonObject.speakerName(): String? =
        optionalString("speakerName")?.trim()?.takeIf {
            it.isNotBlank() && it.length <= 24 &&
                it.toIntOrNull() == null &&
                it !in VIRTUAL_SPEAKER_NAMES && it !in PLACEHOLDER_SPEAKER_NAMES
        }

    /**
     * AI 给新建说话人挑的声音池名；只在逐字命中 pools 时有意义，这里不做校验。
     *
     * 落库前由 [ensureDraftProfiles] 按名查池：查不到（模型自创、池已删）就留空，
     * 让自动选音按性别兜底，绝不自造一个池外键。
     */
    private fun JsonObject.speakerPool(): String =
        optionalString("speakerPool")?.trim()?.takeIf {
            it.isNotBlank() && it.length <= 24
        }.orEmpty()

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

    /** [poolLabel] 是关联声音池的显示名（如「女青年」）；voiceAgeBand 已回归枚举语义， */
    /** 单独传池名保持 AI 提示词里可见的是可读池名而非 young_adult 这类键。 */
    private fun BookCharacterProfile.toPromptMap(poolLabel: String = ""): Map<String, Any?> = mapOf(
        "characterId" to id,
        "name" to name,
        "aliasesJson" to aliasesJson,
        "role" to role,
        "voiceGender" to voiceGender,
        "voiceAgeBand" to poolLabel.ifEmpty { voiceAgeBand },
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
        val speakerPool: String,
        val emotion: String,
        val confidence: Float,
    )

    private data class AiAtomGroup(
        val atomIds: List<String>,
        val roleType: SpeechRoleType,
        val characterId: String?,
        val speakerName: String?,
        val speakerGender: String,
        val speakerPool: String,
        val emotion: String,
        val confidence: Float,
    )

    /**
     * AI 建议新建的说话人：稳定称呼 + 性别 + 建议的声音池名。
     *
     * [poolLabel] 只在**新建**这一张卡时用来定池（逐字取自 pools，查无此池即忽略）；
     * 命中已有档案的说话人不会走到这里，所以它没有"给同人换声音"的机会。
     */
    private data class SpeakerSuggestion(
        val name: String,
        val gender: String,
        val poolLabel: String,
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
         *
         * 2026-10-21 并入旧「AI 分配角色」引擎（[io.legado.app.help.readaloud.cast.AiCastAssignUseCase]）
         * 里那套判定规则中与本链不冲突的部分：称呼归并（多重身份仍是同一人）、禁止把代词与叙述称谓
         * 当主名、同一称谓同一写法、连着引号时说话人通常在交替。泛称那条**保留本链口径**
         * （「大汉/侍卫」是路人，不建卡），没有吸收旧引擎"按身份称谓建档"的写法。
         * 提示词预设里保存的自定义内容会整段覆盖它；归因口径与 JSON 输出契约仍由
         * [ATTRIBUTION_MUST_ANSWER] / [SEGMENT_SCHEMA_RULES] / [ATOM_SCHEMA_RULES] 代码追加。
         * strings.xml 的 ai_prompt_default_analyze_speech 是同一份文本（提示词配置页的默认值/重置目标），
         * 改这里必须同步改它。
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
                "被提到、被称呼、被看见或被想到的人不等于说话人：" +
                "「李元老，您这话太过了」是**别人对李振富说**的话，说话人不是李振富——" +
                "第二人称称呼谁，被称呼者就是听者。\n" +
                "4. 先匹配 characters 里的 characterId。网名、昵称、账号名、群名片、代号、乳名和" +
                "外号首先是已有人物的身份标签：正文出现「X 是 Y 的网名/昵称」或「哦，是 Y」等" +
                "明确映射时，必须复用 Y 的 characterId，不得为 X 另立说话人。" +
                "同一个人物的所有称呼（本名、化名、卧底或马甲身份、职务称谓、排行）都归到同一个角色；" +
                "只有正文表明性别不同、或处于不同年龄阶段（回忆童年与现在）时才当成两个人。" +
                "已有档案把同一个人记成两个名字时，用其真名输出。\n" +
                "5. 说话人是明确的人但没命中任何已有角色时：characterId 留空，把稳定称呼" +
                "（姓名、外号、唯一称谓）写进 speakerName，客户端会为它建临时角色。" +
                "即使这个人物只出现一场，只要有稳定称呼也要写；不确定就留空，不要编造。\n" +
                "6. speakerName 一律写主名，**禁止把代词和叙述称谓当名字**：" +
                "「我」「你」「您」「他」「她」「它」「咱」「俺」「本人」「自己」「人家」「旁白」「叙述者」" +
                "「作者」「读者」这些写法一个都不许出现在 speakerName 里。" +
                "第一人称叙述者说的话要写成他的姓名，全章查不到姓名就统一写「主角」。" +
                "叙述里从不点名的配角（某元老、某干部、某侍女），拿叙述行给的身份称谓当主名" +
                "（如「直系元老」「旁系干部」），整章同一称谓必须同一写法，不要换字、" +
                "也不要编号成「元老甲/元老乙」。\n" +
                "7. 「大汉」「侍卫」「老捕头」「下属」这类一次性职业、群体或外貌泛称是路人：" +
                "characterId 与 speakerName 都留空。但路人只要是人物声音，性别能确认就写 " +
                "male/female，客户端会用对白兜底音色发声，不要因为身份未知就把对白吞成旁白。\n" +
                "8. 连着几段都是引号、中间没有叙述行时，**不要默认是同一个人**：作者写一问一答" +
                "正是这种排版，此时说话人通常在交替。用台词之间的呼应来定人——上一句被「您/你」" +
                "指向的那位是本句的回答者；自称、被提到的亲属与下属关系、语气承接（反问、驳斥、顺承）" +
                "都是依据。例：甲说「我儿子被砍了」→ 接一句「失去爱子的痛苦我不敢妄自揣测」的必定是乙，" +
                "不是甲。\n" +
                "9. speakerGender 只在正文有依据时返回 male/female：明确性别代词（她/他）、" +
                "性别称呼（小姐、公子、夫人、姑娘、大哥、小妹妹）、「我叫……」类自述都是依据；" +
                "姓名和职业本身不是证据。输出前复核每个 male/female 能否引用到正文线索，" +
                "找不到就写 unknown，不要为了选音瞎猜；也尽量把称呼、代词提供的线索找尽，" +
                "不要轻易留 unknown。\n" +
                "10. 新建说话人（characterId 留空且 speakerName 给了稳定称呼）时，从 pools 里挑" +
                "最贴合其性别、年龄、气质的一个池名逐字写进 speakerPool；" +
                "characters 里已有的角色**一律不要给 speakerPool**，那等于给同一个人换声音。" +
                "pools 为空、或拿不准哪个池贴合就省略这个字段，客户端会自己按性别兜底选音。\n" +
                "11. 无法确认是人物声音时才按 narrator 处理；不得因为说话人身份或性别未知" +
                "而吞掉已确认的对白。\n" +
                "12. emotion 只在文本有明确语气线索时给，不确定写 neutral；confidence 如实反映" +
                "证据强度，高置信度永远不能代替证据。"

        /**
         * 归因口径·必答（朗读设置里该档的默认值）。
         *
         * 来自旧「AI 分配角色」引擎的「逐条必答」要求：沉默、非语言声响、人群齐声都要给说话人。
         * 漏分的代价是整句掉回兜底音，比错分更难归因。
         */
        private const val ATTRIBUTION_MUST_ANSWER =
            "归因口径（必答）：输入的每一个分段都要给出一条结论，一个都不许漏。" +
            "只表示沉默的『……』、咳血/闷哼/惨叫/呜啊啊这类非语言声响、人群齐声与四周惊呼、" +
            "内心独白的转述，都是某个人物在这一句上发出的声音——按上下文判断是谁发出的并分配给他；" +
            "同一段里两个人同声接话分不清时，归给上下文更贴合的那一个。" +
            "不要因为台词短、没有实际内容或听着不像说话就跳过该条。"

        /**
         * 归因口径·宁缺毋滥。
         *
         * 旧引擎的「允许漏分」口径：没有明确证据就不给，让那句话走默认音。
         * 两档共用同一份解析器与同一套 JSON 契约，漏分在两种模式下都有现成落点：分段模式不返回
         * 该条（保留规则结果），原子模式把该句单独归成 unknown 组（未引用到的原子同样按未识别走）。
         */
        private const val ATTRIBUTION_EVIDENCE_ONLY =
            "归因口径（宁缺毋滥）：只有这句话的说话人有明确证据（被点名、说话动作归属、" +
            "上下文直接延续）时才把该条判给某个人物；任何不确定都不要给说话人——" +
            "分段复核时不要返回该条，原子理解时按 narrator/unknown 归组，" +
            "两种情况下客户端都保留规则结论走默认音。宁可漏分，不可错分。" +
            "沉默与非语言声响同样按这个标准处理。"

        /** 分段复核模式的 JSON 输出契约，必须与 [parseSegmentDecisions] 的解析严格一致 */
        private const val SEGMENT_SCHEMA_RULES =
            "\n只返回一个 JSON 对象，不要 Markdown 与解释，不要发明 ID：" +
                "\n{\"segments\":[{\"segmentId\":\"输入中的 ID\",\"roleType\":" +
                "\"narrator|character|thought|unknown\",\"characterId\":\"characters 中的 ID 或 null\"," +
                "\"speakerName\":\"稳定称呼或 null\",\"speakerPool\":\"逐字取自 pools，可省略\"," +
                "\"speakerGender\":\"male|female|unknown\"," +
                "\"emotion\":\"neutral|cheerful|sad|angry|fearful|surprised|disgusted|whispering|calm\"," +
                "\"confidence\":0.88}]}。" +
                "同一个分段最多返回一条决策；要不要逐条返回，按上面的归因口径执行。"

        /** 原子理解模式的 JSON 输出契约，必须与 [parseAtomGroups] 的解析严格一致 */
        private const val ATOM_SCHEMA_RULES =
            "\n只返回一个 JSON 对象，不要 Markdown 与解释，不要发明 ID：" +
                "\n{\"segments\":[{\"atomIds\":[\"输入中的原子 ID\"],\"roleType\":" +
                "\"narrator|character|thought|unknown\",\"characterId\":\"characters 中的 ID 或 null\"," +
                "\"speakerName\":\"稳定称呼或 null\",\"speakerPool\":\"逐字取自 pools，可省略\"," +
                "\"speakerGender\":\"male|female|unknown\"," +
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

        /**
         * 占位主名黑名单：代词与叙述称谓不是任何人物的名字。
         *
         * 从旧「AI 分配角色」引擎原样吸收（连纯数字的「1/2/3」一起挡——那是模型给台词编号，
         * 不是名字）。这类名字既不参与建卡，也不该出现在名册里让后续章节复用。
         */
        private val PLACEHOLDER_SPEAKER_NAMES = setOf(
            "我", "你", "您", "他", "她", "它", "咱", "俺", "本人", "自己", "人家", "别人",
            "旁白", "叙述者", "叙述", "作者", "读者", "主播", "系统", "无", "未知", "说话人",
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
