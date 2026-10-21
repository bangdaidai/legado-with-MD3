package io.legado.app.domain.usecase

import io.legado.app.constant.AppLog
import io.legado.app.domain.gateway.CastSpeechGateway
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ContentSplitPolicy
import io.legado.app.domain.model.readaloud.SpeechAnalysisMode
import io.legado.app.domain.model.readaloud.SpeechAttributionStrictness
import io.legado.app.domain.model.readaloud.SpeechPlanItem
import io.legado.app.help.readaloud.cast.CastSpeechOverlay
import kotlinx.coroutines.CancellationException

/**
 * 「这一章该怎么念」的唯一入口：分段 → 识别说话人 → 选音色 → 套用分配表覆盖。
 *
 * 存在的理由是历史上这段编排写在朗读服务里，分镜页只截取了中间两步（分析 + 选音色）。
 * 两边因此对同一条 cue 给出不同答案：分镜页显示的是分配表覆盖**之前**的音色，实际发声是
 * 覆盖**之后**的，界面上写着一个人、耳朵里是另一个人的声音；整段/整页划分下覆盖层还会
 * 再切一刀，分镜页那��行甚至根本不对应任何一条实际发声的单元。
 *
 * 两侧都调这一个用例，"看到的"和"听到的"就来自同一份计划。
 *
 * 关掉「多角色朗读」且本书没有生效的正则角色时返回空列表：调用方据此回到用户选的默认
 * 引擎整章直读（语义见 [io.legado.app.service.BaseReadAloudService.buildSpeechPlan]）。
 */
class ResolveEffectiveSpeechPlanUseCase(
    private val prepareChapterSpeechPlan: PrepareChapterSpeechPlanUseCase,
    private val buildSpeechPlan: BuildSpeechPlanUseCase,
    private val castGateway: CastSpeechGateway,
    private val voiceGateway: ReadAloudVoiceGateway,
    private val readAloudSettingsGateway: ReadAloudSettingsGateway,
) {

    suspend operator fun invoke(
        bookUrl: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
        splitPolicy: ContentSplitPolicy,
        preferredDefaultVoiceId: String? = null,
        analysisMode: SpeechAnalysisMode? = null,
        analysisReasoningLevel: AiReasoningLevel? = null,
        /** 发起者标注，进 AI 日志与回落提示。 */
        source: String = "朗读",
        /** 归因口径覆盖；null 表示读设置里那一份。 */
        attributionStrictness: SpeechAttributionStrictness? = null,
    ): List<SpeechPlanItem> {
        if (bookUrl.isEmpty() || paragraphs.isEmpty()) return emptyList()
        val settings = readAloudSettingsGateway.currentSettings
        val multiSpeakerOn = settings.useMultiSpeaker
        // 「多角色朗读」是唯一的发声开关：关掉就没有任何计划，整章回到用户在朗读设置里
        // 选的默认引擎。「多角色分配」只管正文胶囊，不参与决定用谁的声音念。
        //
        // 正则角色不跟这个开关：它只被自己那条规则的启用状态管。正则必须落在播放计划上
        // 才能只换命中那几个字的声音，所以这本书只要有生效的正则角色就照样生成计划
        // （所有段用默认音色）。生效判定在 CastSpeechGateway.regexEffects。
        val regexOn = runCatching { castGateway.regexEffects(bookUrl).isNotEmpty() }
            .getOrDefault(false)
        if (!multiSpeakerOn && !regexOn) return emptyList()

        val plan = runCatching {
            prepareChapterSpeechPlan(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                paragraphs = paragraphs,
                preferredDefaultVoiceId = preferredDefaultVoiceId,
                analysisMode = analysisMode
                    ?: SpeechAnalysisMode.fromStorage(settings.speechAnalysisMode)
                        .let { if (multiSpeakerOn) it else SpeechAnalysisMode.Rule },
                analysisReasoningLevel = analysisReasoningLevel
                    ?: AiReasoningLevel.fromStorage(
                        settings.speechAnalysisReasoningLevel,
                        AiReasoningLevel.OFF,
                    ),
                useMultiSpeaker = multiSpeakerOn,
                policy = splitPolicy,
                source = source,
                attributionStrictness = attributionStrictness
                    ?: SpeechAttributionStrictness.fromStorage(settings.speechAttributionStrictness),
            )
        }.onFailure {
            // 新一轮起播掐旧轮时也走到这里：取消不是失败，不得记"生成失败"再拿空计划继续——
            // 那会让被取代的轮次以「原朗读方式」差一点就把旧章节内容错误上屏。
            if (it is CancellationException) throw it
            AppLog.put("生成多角色朗读计划失败，使用原朗读方式\n${it.localizedMessage}", it)
        }.getOrDefault(emptyList())

        // 覆盖层要读分配表、角色库、声音池，任何一次查询出错都不能把整章的朗读准备带走：
        // 这里的调用方是 `newReadAloud`，它外层是 `execute{}.onError{ pauseReadAloud() }`，
        // 抛出会停在章末（切下一章时最明显），所以降级成「这一章不做多角色覆盖」继续念。
        return runCatching {
            CastSpeechOverlay.apply(
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                paragraphs = paragraphs,
                plan = plan,
                castGateway = castGateway,
                voiceGateway = voiceGateway,
            )
        }.onFailure {
            AppLog.put("多角色分配覆盖失败，本章按原计划朗读\n${it.localizedMessage}", it)
        }.getOrDefault(plan)
    }

    /** 分镜页「重新分析」时主动解除本书 AI 失败冷却。 */
    fun clearAiCooldown(bookUrl: String) = prepareChapterSpeechPlan.clearAiCooldown(bookUrl)

    /**
     * 把库里存下来的分段按当前音色绑定算成计划，不重跑分析。
     *
     * 分镜页看非当前章、或者当前章已有分析缓存时走这条：分段是当时落库的，但**音色必须
     * 现算**——用户改过分配、停用过音色之后，库里那份分段对应的仍是旧音色。
     */
    suspend fun planFromSegments(
        bookUrl: String,
        segments: List<ChapterSpeechSegment>,
        characterPerformances: Map<String, CharacterPerformanceProfile> = emptyMap(),
        useMultiSpeaker: Boolean = readAloudSettingsGateway.currentSettings.useMultiSpeaker,
    ): List<SpeechPlanItem> = buildSpeechPlan(
        bookUrl = bookUrl,
        segments = segments,
        characterPerformances = characterPerformances,
        useMultiSpeaker = useMultiSpeaker,
    )

    /**
     * 只套分配表覆盖层，不碰分析与选音。
     *
     * [ResolveEffectiveSpeechPlanUseCase.invoke] 的后半段单独暴露给分镜页：它拿到的是
     * 落库分段，缺的是覆盖那一步。跳过覆盖显示出来的就是覆盖之前的音色，也就是过去
     * "分镜页写的音色和实际发声的不是一个"的原因。
     */
    suspend fun overlayOnSegments(
        bookUrl: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
        plan: List<SpeechPlanItem>,
    ): List<SpeechPlanItem> = CastSpeechOverlay.apply(
        bookUrl = bookUrl,
        chapterIndex = chapterIndex,
        paragraphs = paragraphs,
        plan = plan,
        castGateway = castGateway,
        voiceGateway = voiceGateway,
    )
}