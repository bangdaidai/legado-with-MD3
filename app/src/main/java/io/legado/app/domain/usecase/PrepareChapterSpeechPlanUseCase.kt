package io.legado.app.domain.usecase

import io.legado.app.constant.AppLog
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysisResult
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ContentSplitPolicy
import io.legado.app.domain.model.readaloud.SpeechPlanItem
import io.legado.app.domain.model.readaloud.SpeechAnalysisMode
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.help.readaloud.segment.RuleBasedSpeechSegmenter

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
    ): List<SpeechPlanItem> {
        if (paragraphs.isEmpty()) return emptyList()
        val requestedMode = analysisMode
        val ruleVersion = ruleResolverVersion(policy)
        val resolverVersion = if (requestedMode == SpeechAnalysisMode.Rule) {
            ruleVersion
        } else {
            runCatching { refineSpeechWithAi.resolverVersion(bookUrl, requestedMode, policy) }
                .onFailure {
                    // 没配 AI 模型时这里就抛了，静默回落会让用户以为 AI 模式生效了
                    notifyFallback("AI 分析未启用，已按规则模式朗读", it)
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
                )
            }.onSuccess {
                lastNotifiedFallback = null
            }.onFailure {
                notifyFallback("AI 分析说话人失败，已回落规则结果", it)
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
     */
    private fun notifyFallback(reason: String, error: Throwable) {
        val message = "$reason\n${error.localizedMessage}"
        val repeated = message == lastNotifiedFallback
        lastNotifiedFallback = message
        AppLog.put(message, error, !repeated)
    }

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
