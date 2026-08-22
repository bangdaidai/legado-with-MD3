package io.legado.app.domain.usecase

import io.legado.app.constant.AppLog
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysisResult
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
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

    suspend operator fun invoke(
        bookUrl: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
        preferredDefaultVoiceId: String? = null,
        analysisMode: SpeechAnalysisMode = SpeechAnalysisMode.Rule,
        useMultiSpeaker: Boolean = true,
    ): List<SpeechPlanItem> {
        if (paragraphs.isEmpty()) return emptyList()
        val requestedMode = analysisMode
        val resolverVersion = if (requestedMode == SpeechAnalysisMode.Rule) {
            RuleBasedSpeechSegmenter.VERSION
        } else {
            runCatching { refineSpeechWithAi.resolverVersion(bookUrl, requestedMode) }
                .getOrDefault(RuleBasedSpeechSegmenter.VERSION)
        }
        val effectiveMode = if (resolverVersion == RuleBasedSpeechSegmenter.VERSION) {
            SpeechAnalysisMode.Rule
        } else {
            requestedMode
        }
        val analysis = analyzeChapterSpeech(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            paragraphs = paragraphs,
            resolverVersion = resolverVersion,
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
                )
            }.onFailure {
                // 静默回落会让「AI 没配模型」和「AI 返回不合法」看起来完全一样
                AppLog.put("AI 分析说话人失败，已回落规则结果\n${it.localizedMessage}", it, true)
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
