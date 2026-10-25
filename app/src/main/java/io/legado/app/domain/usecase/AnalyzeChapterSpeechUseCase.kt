package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysis
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysisResult
import io.legado.app.domain.model.readaloud.ChapterSpeechSegment
import io.legado.app.domain.model.readaloud.ContentSplitPolicy
import io.legado.app.domain.model.readaloud.SpeechAnalysisStatus
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.help.readaloud.segment.RuleBasedSpeechSegmenter

/**
 * 「这一章的分镜」的唯一读写入口：有结果就整份照用，没结果才按当前设置切一遍。
 *
 * 一句话口径：**没人点「重新分析」，库里那一份就不动。**划分方式、分析模式、模型、提示词、
 * 归因口径都只决定**还没跑过的章**怎么跑。
 */
class AnalyzeChapterSpeechUseCase(
    private val chapterSpeechGateway: ChapterSpeechGateway,
) {

    suspend operator fun invoke(
        bookUrl: String,
        chapterIndex: Int,
        paragraphs: List<CanonicalSpeechParagraph>,
        resolverVersion: String = RuleBasedSpeechSegmenter.VERSION,
        policy: ContentSplitPolicy,
        /**
         * 是否允许在「一个划分单元」内部按引号/说话提示再拆出对白段（AI 档才需要）。
         *
         * 整段/按页划分下策略本身不许拆，规则会把整段标成旁白，AI 没有候选段可判。
         * 这里只放开「谁在说话」的边界：句号处仍不切，所以不会制造句末停顿。
         * 纯规则档保持 false：结果直接拿来选音，多拆只会多出朗读单元。
         */
        splitDialogueInsideUnit: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): ChapterSpeechAnalysisResult {
        val contentHash = SpeechIdentity.chapterContentHash(paragraphs)
        /*
         * 读的时候只看「这一章有没有结果」，有就整份照用——不拿当前设置去比对，比对不上就重切重分析。
         *
         * 划分方式 / 分析模式 / 模型 / 提示词 / 归因口径全都写在 resolverVersion 里，过去一比不一致
         * 就当没命中：用户改一次设置，已经做好的分镜当场作废、下次听到那一章再烧一次 AI。
         * 那些设置只管**没跑过的章**，要让它管到旧章，用户点右上角「重新分析」（那里先删掉这一行）。
         *
         * 唯一还算数的比对是正文本身：那几段是不是量在当前这份正文上
         * （[SpeechIdentity.alignsWithParagraphs]，与划分粒度无关的判据）。重新下载过正文、改过替换
         * 规则、开关过「显示角色」都会让它对不上——那种情况旧分段的坐标已经不在现在的字上，必须重跑。
         * 只换划分方式不会：单元再怎么切，同一个位置还是同一个字。
         */
        val stored = chapterSpeechGateway.getStoredAnalysis(bookUrl, chapterIndex)
        val storedSegments = stored?.let { chapterSpeechGateway.getSegments(it.id) }.orEmpty()
        if (stored != null && stored.status in REUSABLE_STATUSES &&
            // Failed 记的是「AI 复核那一步失败」，规则分段本身是好的，而且状态要原样带出去，
            // RefineSpeechWithAiUseCase 才能看到「刚失败过」并进入冷却。分段真没落库时才重跑。
            storedSegments.isNotEmpty() &&
            SpeechIdentity.alignsWithParagraphs(paragraphs, storedSegments)
        ) {
            return ChapterSpeechAnalysisResult(
                analysis = stored,
                segments = storedSegments,
                fromCache = true,
                // 粒度一致才敢用分段行上的段内相对坐标（重认说话人靠它）；不一致就什么都不动
                paragraphSpaceMatches = stored.contentHash == contentHash,
            )
        }

        val analysisId = SpeechIdentity.analysisId(
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            contentHash = contentHash,
            resolverVersion = resolverVersion,
        )
        // 只对 AI 档、且策略本身禁止段内拆分（整段/按页）时放开引号与说话提示的拆分。
        // 这一步只影响落库的分段粒度：段落还是那个段落，句号处不切，段内停顿节奏不变；
        // 切出来的对白段才进 AI 候选，旁白段（0.9/0.95）不进。
        val segmentPolicy = if (splitDialogueInsideUnit && !policy.allowRoleSplits) {
            policy.copy(allowRoleSplits = true)
        } else {
            policy
        }
        val drafts = RuleBasedSpeechSegmenter.segment(paragraphs, segmentPolicy)
        val segments = drafts.map { draft ->
            ChapterSpeechSegment(
                id = SpeechIdentity.segmentId(
                    analysisId = analysisId,
                    paragraphIndex = draft.paragraphIndex,
                    start = draft.start,
                    end = draft.end,
                ),
                analysisId = analysisId,
                bookUrl = bookUrl,
                chapterIndex = chapterIndex,
                paragraphIndex = draft.paragraphIndex,
                start = draft.start,
                end = draft.end,
                chapterPosition = draft.chapterPosition,
                text = draft.text,
                roleType = draft.roleType,
                emotion = draft.emotion,
                confidence = draft.confidence,
                source = draft.source,
                createdAt = now,
                updatedAt = now,
            )
        }
        val status = if (segments.any { it.roleType.requiresSpeakerResolution }) {
            SpeechAnalysisStatus.Partial
        } else {
            SpeechAnalysisStatus.Success
        }
        val analysis = ChapterSpeechAnalysis(
            id = analysisId,
            bookUrl = bookUrl,
            chapterIndex = chapterIndex,
            contentHash = contentHash,
            resolverVersion = resolverVersion,
            characterRevision = "",
            status = status,
            // 同一条分析（半成品或空的分段）继续写时留着她第一次的创建时间
            createdAt = stored?.takeIf { it.id == analysisId }?.createdAt ?: now,
            updatedAt = now,
        )
        chapterSpeechGateway.saveAnalysis(analysis, segments)
        return ChapterSpeechAnalysisResult(analysis, segments, fromCache = false)
    }

    private val SpeechRoleType.requiresSpeakerResolution: Boolean
        get() = this == SpeechRoleType.Character || this == SpeechRoleType.Thought

    private companion object {
        /** 分段可以直接复用的状态；`Pending` / `Running` 是半成品，只能重跑 */
        val REUSABLE_STATUSES = setOf(
            SpeechAnalysisStatus.Success,
            SpeechAnalysisStatus.Partial,
            SpeechAnalysisStatus.Failed,
        )
    }
}
