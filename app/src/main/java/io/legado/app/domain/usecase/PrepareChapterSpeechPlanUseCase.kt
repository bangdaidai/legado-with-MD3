package io.legado.app.domain.usecase

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysisResult
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ContentSplitPolicy
import io.legado.app.domain.model.readaloud.SpeechAnalysisMode
import io.legado.app.domain.model.readaloud.SpeechPlanItem
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.help.readaloud.segment.RuleBasedSpeechSegmenter
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Builds the persisted speech plan used by a read-aloud session.
 *
 * Segmentation is pagination independent, while speaker resolution is refreshed when the
 * character database changes. Keeping the orchestration here prevents Android services from
 * knowing about the analysis cache or character repository.
 */
class PrepareChapterSpeechPlanUseCase(
    private val analyzeChapterSpeech: AnalyzeChapterSpeechUseCase,
    private val resolveLocalSpeakers: ResolveLocalSpeakersUseCase,
    private val refineSpeechWithAi: RefineSpeechWithAiUseCase,
    private val autoAssignVoiceBindings: AutoAssignVoiceBindingsUseCase,
    private val buildSpeechPlan: BuildSpeechPlanUseCase,
) {

    /**
     * 上一次已经弹过的回落原因。
     *
     * 每次重新起读（换章、选段落）都会重跑一遍分析，同一个失败原因不去重的话会连着弹很多次 toast。
     */
    @Volatile
    private var lastNotifiedFallback: String? = null

    suspend operator fun invoke(
        bookUrl: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
        preferredDefaultVoiceId: String? = null,
        analysisMode: SpeechAnalysisMode = SpeechAnalysisMode.Rule,
        analysisReasoningLevel: AiReasoningLevel = AiReasoningLevel.OFF,
        useMultiSpeaker: Boolean = true,
        policy: ContentSplitPolicy,
        /** 发起者标注（朗读/预合成），进 AI 日志与回落提示，区分同一章的两条分析链 */
        source: String = "朗读",
    ): List<SpeechPlanItem> {
        if (paragraphs.isEmpty()) return emptyList()
        val requestedMode = analysisMode
        val ruleVersion = ruleResolverVersion(policy)
        val resolverVersion = if (requestedMode == SpeechAnalysisMode.Rule) {
            ruleVersion
        } else {
            runCatching { refineSpeechWithAi.resolverVersion(bookUrl, requestedMode, policy) }
                .onFailure {
                    if (it.isCancelledByNextRound()) {
                        // 本轮被新一轮起播裸 cancel() 掐掉，不是「没启用 AI」，不许弹回落 toast
                        AppLog.put(
                            "AI 分析版本号未取到（本轮已被新起播取消，非失败）\n${it.describeError()}",
                            it,
                        )
                    } else {
                        // 没配 AI 模型时这里就抛了，静默回落会让用户以为 AI 模式生效了
                        notifyFallback("AI 分析未启用，已按规则模式朗读", it, chapterIndex, source)
                    }
                }
                .getOrDefault(ruleVersion)
        }
        val effectiveMode = if (isRuleResolverVersion(resolverVersion, policy)) {
            SpeechAnalysisMode.Rule
        } else {
            requestedMode
        }
        val analysis = analyzeChapterSpeech(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            paragraphs = paragraphs,
            resolverVersion = resolverVersion,
            policy = policy,
        )
        val locallyResolved = resolveLocalSpeakers(
            analysisResult = analysis,
            paragraphs = paragraphs,
        )
        val resolved = if (effectiveMode == SpeechAnalysisMode.Rule) {
            locallyResolved
        } else {
            runCatching {
                refineSpeechWithAi(
                    analysisResult = locallyResolved,
                    paragraphs = paragraphs,
                    mode = effectiveMode,
                    reasoningLevel = analysisReasoningLevel,
                    policy = policy,
                    source = source,
                )
            }.onSuccess {
                lastNotifiedFallback = null
            }.onFailure {
                if (it.isCancelledByNextRound()) {
                    // 取消≠失败：不弹「分析失败」，也不该让用户以为回落了规则结果
                    AppLog.put(
                        "AI 说话人分析被新一轮起播取消，非失败\n${it.describeError()}",
                        it,
                    )
                } else {
                    notifyFallback("AI 分析说话人失败，已回落规则结果", it, chapterIndex, source)
                }
            }.getOrDefault(locallyResolved)
        }
        if (useMultiSpeaker) {
            // 必须在 buildSpeechPlan 之前落库，否则新分配的音色要等下一章才生效
            runCatching {
                autoAssignVoiceBindings(
                    bookUrl = bookUrl,
                    performances = resolved.speakingPerformances(),
                )
            }.onFailure {
                AppLog.put("自动分配音色失败\n${it.localizedMessage}", it)
            }
        }
        return buildSpeechPlan(
            bookUrl = bookUrl,
            segments = resolved.segments,
            preferredDefaultVoiceId = preferredDefaultVoiceId,
            characterPerformances = resolved.characterPerformances.associateBy { it.characterId },
            useMultiSpeaker = useMultiSpeaker,
        ).also { plan -> logPlanSummary(chapterIndex, effectiveMode, resolved, plan) }
    }

    /**
     * 分镜页「重新分析」时主动解除本书 AI 失败冷却，避免被 [refineSpeechWithAi] 的本书级冷却挡住。
     */
    fun clearAiCooldown(bookUrl: String) {
        refineSpeechWithAi.clearAiCooldown(bookUrl)
    }

    /**
     * 回落提示按「原因」去重：只有换了新原因才弹 toast，重复的只进日志。
     * 同时发 [EventBus.ALOUD_AI_FALLBACK] 让朗读服务把原因 toast 给用户——
     * 静默回落曾让用户以为 AI 生效了，听半天单角色才发现不对。
     */
    private fun notifyFallback(
        reason: String,
        error: Throwable,
        chapterIndex: Int? = null,
        source: String = "朗读",
    ) {
        val prefix = chapterIndex?.let { "第${it + 1}章（$source）" }.orEmpty()
        val message = "$prefix$reason\n${error.describeError()}"
        val repeated = message == lastNotifiedFallback
        lastNotifiedFallback = message
        AppLog.put(message, error, !repeated)
        postEvent(EventBus.ALOUD_AI_FALLBACK, message)
    }

    /**
     * 本轮是被新一轮起播掐掉的，不是 AI 真出了问题。
     *
     * `newReadAloud` 用无因 `cancel()` 砍旧轮，抛出来的 CancellationException 连消息都是 null，
     * 之前一律按失败弹 toast，才有「明明配了模型却提示未启用/失败」。超时是真失败，必须排除。
     */
    private fun Throwable.isCancelledByNextRound(): Boolean =
        this is CancellationException && this !is TimeoutCancellationException

    /** 裸 cancel() 等无消息异常 localizedMessage 为 null，toast 会显示一个天书「null」，退回类名 */
    private fun Throwable.describeError(): String =
        localizedMessage ?: javaClass.simpleName

    /** 只给本章真正开口的角色自动选音，别把整本人物表的音色都占掉 */
    private fun ChapterSpeechAnalysisResult.speakingPerformances(): List<CharacterPerformanceProfile> {
        val speaking = segments
            .filter {
                it.roleType == SpeechRoleType.Character || it.roleType == SpeechRoleType.Thought
            }
            .mapNotNullTo(hashSetOf()) { it.characterId }
        return characterPerformances.filter { it.characterId in speaking }
    }

    /** 分镜页只看当前章，这条日志覆盖后台朗读时区分「没分段」「没识别到角色」「没分配音色」。 */
    private fun logPlanSummary(
        chapterIndex: Int,
        mode: SpeechAnalysisMode,
        resolved: ChapterSpeechAnalysisResult,
        plan: List<SpeechPlanItem>,
    ) {
        val dialogue = resolved.segments.count {
            it.roleType == SpeechRoleType.Character || it.roleType == SpeechRoleType.Thought
        }
        AppLog.putDebug(
            "多角色朗读计划 ch=$chapterIndex mode=${mode.storageValue} " +
                "分段=${resolved.segments.size} 对白=$dialogue " +
                "已识别角色=${resolved.segments.count { it.characterId != null }} " +
                "角色卡=${resolved.characterPerformances.size} " +
                "已分配音色=${plan.count { it.voice != null }}/${plan.size} " +
                "缓存=${resolved.fromCache} 状态=${resolved.analysis.status.storageValue}"
        )
    }
}

/**
 * 纯规则分段的分析标识。
 *
 * 内容划分方式会改变切分结果，必须参与版本号，否则切换划分方式后会命中旧划分的分析缓存。
 */
fun ruleResolverVersion(policy: ContentSplitPolicy): String =
    "${RuleBasedSpeechSegmenter.VERSION}:${policy.identifier}"

/** 判断某个解析器版本是否就是该划分方式下的纯规则分段。 */
fun isRuleResolverVersion(resolverVersion: String, policy: ContentSplitPolicy): Boolean =
    resolverVersion == ruleResolverVersion(policy)
