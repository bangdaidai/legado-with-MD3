package io.legado.app.domain.usecase

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysisResult
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ContentSplitPolicy
import io.legado.app.domain.model.readaloud.SpeechAnalysisMode
import io.legado.app.domain.model.readaloud.SpeechAttributionStrictness
import io.legado.app.domain.model.readaloud.SpeechPlanItem
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.help.readaloud.cast.CastSegmentFinalizer
import io.legado.app.help.readaloud.cast.VoiceLabelStore
import io.legado.app.help.readaloud.cast.VoicePoolStore
import io.legado.app.help.readaloud.segment.RuleBasedSpeechSegmenter
import io.legado.app.utils.postEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Builds the persisted speech plan used by a read-aloud session.
 *
 * 分段与翻页无关；说话人识别只在**同一套划分**上随人物档案刷新（改过划分方式时那一份分段不动，
 * 见 [ResolveLocalSpeakersUseCase]）。编排留在这里，Android 服务不需要知道分析缓存和角色库。
 *
 * 已经跑过的章整份照用：这一趟只做「读那一份 + 现算音色 + 套定稿」，不再重切也不再发 AI。
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
        /** 归因口径：决定发给模型的口径文本，也写进这一轮**新落下**的那份版本号；已经判过的章不看它。 */
        attributionStrictness: SpeechAttributionStrictness = SpeechAttributionStrictness.MustAnswer,
        /**
         * 分镜页「重新分析」：用户显式要求重跑，不受本书 AI 失败冷却影响，也不等同章在飞的那一轮。
         * 只由手动入口传 true；朗读/预合成永远走冷却，免得一次失败把整本书的 token 反复烧。
         */
        ignoreAiCooldown: Boolean = false,
    ): List<SpeechPlanItem> {
        if (paragraphs.isEmpty()) return emptyList()
        val requestedMode = analysisMode
        val ruleVersion = ruleResolverVersion(policy)
        val resolverVersion = if (requestedMode == SpeechAnalysisMode.Rule) {
            ruleVersion
        } else {
            runCatching {
                refineSpeechWithAi.resolverVersion(requestedMode, policy, attributionStrictness)
            }
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
            // AI 档才需要把段内对白拆出来：整段/按页划分下规则把整段标成旁白，
            // AI 连候选段都没有。只放开引号/说话提示处的拆分，句号处仍不切，
            // 保住「整段不按句停顿」；纯规则档不动，避免多出朗读单元。
            splitDialogueInsideUnit = effectiveMode != SpeechAnalysisMode.Rule && useMultiSpeaker,
        )
        val locallyResolved = resolveLocalSpeakers(
            analysisResult = analysis,
            paragraphs = paragraphs,
        )
        /*
         * 「这一章已经有结果」就不再过 AI 复核：分析模式、模型、提示词、归因口径全都只写进
         * 版本号，过去版本号一变就重发一次请求，等于用户改一次设置把自己做好的分镜冲掉。
         *
         * 真的会发请求的只剩两种：这一章没跑过（或正文换过、对不上现在的坐标），
         * 以及用户在分镜页点「重新分析」——那里先删掉库里那一份再跑这一条链。
         */
        val resolved = if (effectiveMode == SpeechAnalysisMode.Rule || analysis.fromCache) {
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
                    strictness = attributionStrictness,
                    ignoreCooldown = ignoreAiCooldown,
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
        /*
         * 定稿回放：把正文胶囊分好的那一句写进 segments（characterId + userLocked），当场生效。
         *
         * 放在选音之前，于是三件事一次做完：这一句立刻用那个角色的声音念（不用等下一章）、
         * 只在分配里出现而 AI 没认出的角色也会走下面那步自动补音、重跑分析时那几处
         * 「跳过锁定段」的判断开始起作用。
         * 库里那一份分段是按别的划分方式切的也没关系：回放比的是章内绝对坐标，
         * 与粒度无关；正文真换过（对不上现在的位置）时它自己会整趟跳过。
         * 一条分配都没有的书在这里零成本（读一次分配表就返回）。
         */
        val finalized = runCatching {
            resolved.copy(
                segments = CastSegmentFinalizer.replay(
                    bookUrl = bookUrl,
                    chapterIndex = chapterIndex,
                    paragraphs = paragraphs,
                    segments = resolved.segments,
                ),
            )
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.put("定稿回放失败，本章按分析结果朗读\n${it.localizedMessage}", it)
        }.getOrDefault(resolved)
        if (useMultiSpeaker) {
            // 必须在 buildSpeechPlan 之前落库，否则新分配的音色要等下一章才生效
            runCatching {
                autoAssignVoiceBindings(
                    bookUrl = bookUrl,
                    performances = finalized.speakingPerformances(),
                    // 音色的结构化性别与年龄段都由这一份派生（音色行自己两样都没有列）：
                    // 用户标注 > 声音池分类，缺的才退回引擎声明 / 名字启发。
                    // 以前只把年龄段传进去、性别丢掉（S3②），池分类与标注里的「女」没人读。
                    voiceClassification = VoiceLabelStore.classificationMap(),
                )
            }.onFailure {
                AppLog.put("自动分配音色失败\n${it.localizedMessage}", it)
            }
        }
        return buildSpeechPlan(
            bookUrl = bookUrl,
            segments = finalized.segments,
            preferredDefaultVoiceId = preferredDefaultVoiceId,
            characterPerformances = finalized.characterPerformances.associateBy { it.characterId },
            useMultiSpeaker = useMultiSpeaker,
        ).also { plan -> logPlanSummary(chapterIndex, effectiveMode, finalized, plan) }
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
 * 内容划分方式会改变切分结果，所以它进这一串；**但它已经不是复用判据**——
 * 改了划分方式不会让库里那一份作废、也不会重跑（读侧只看「这一章有没有结果」，
 * 见 [AnalyzeChapterSpeechUseCase]）。这一串只说明**新写下的那一份**当时是按哪一档跑的。
 */
fun ruleResolverVersion(policy: ContentSplitPolicy): String =
    "${RuleBasedSpeechSegmenter.VERSION}:${policy.identifier}"

/** 判断某个解析器版本是否就是该划分方式下的纯规则分段。 */
fun isRuleResolverVersion(resolverVersion: String, policy: ContentSplitPolicy): Boolean =
    resolverVersion == ruleResolverVersion(policy)
