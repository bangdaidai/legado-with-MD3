package io.legado.app.domain.usecase

import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.domain.gateway.BookKnowledgeGateway
import io.legado.app.domain.gateway.ChapterSpeechGateway
import io.legado.app.domain.model.readaloud.CanonicalSpeechParagraph
import io.legado.app.domain.model.readaloud.ChapterSpeechAnalysisResult
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.SpeakerCharacter
import io.legado.app.domain.model.readaloud.SpeechAnalysisStatus
import io.legado.app.domain.model.readaloud.SpeechIdentity
import io.legado.app.domain.model.readaloud.SpeechResolutionSource
import io.legado.app.domain.model.readaloud.SpeechRoleType
import io.legado.app.help.readaloud.cast.VoicePoolStore
import io.legado.app.help.readaloud.resolve.LocalCharacterSpeakerResolver
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ResolveLocalSpeakersUseCase(
    private val bookKnowledgeGateway: BookKnowledgeGateway,
    private val chapterSpeechGateway: ChapterSpeechGateway,
) {

    suspend operator fun invoke(
        analysisResult: ChapterSpeechAnalysisResult,
        paragraphs: List<CanonicalSpeechParagraph>,
        now: Long = System.currentTimeMillis(),
    ): ChapterSpeechAnalysisResult {
        val profiles = withContext(Dispatchers.IO) {
            bookKnowledgeGateway.getCharacterProfiles(
                bookUrl = analysisResult.analysis.bookUrl,
                limit = 200,
                includeDrafts = true,
            )
                // 草稿角色卡当「临时说话人」用：人物列表里不混进正式角色，但必须能参与发音路由，
                // 否则凡是没进正式角色卡的说话人都会掉到旁白兜底音。
                .filter {
                    it.status == BookCharacterProfile.STATUS_ACTIVE ||
                        it.status == BookCharacterProfile.STATUS_DRAFT
                }
        }
        val characters = profiles.map { profile ->
            SpeakerCharacter(
                id = profile.id,
                name = profile.name,
                aliases = GSON.fromJsonArray<String>(profile.aliasesJson).getOrNull().orEmpty(),
                role = profile.role,
                voiceGender = profile.voiceGender,
                voiceAgeBand = profile.voiceAgeBand,
                updatedAt = profile.updatedAt,
            )
        }
        val characterPerformances = profiles.map { profile ->
            CharacterPerformanceProfile(
                characterId = profile.id,
                role = profile.role,
                voiceGender = profile.voiceGender,
                voiceAgeBand = profile.voiceAgeBand,
                // 池显示名单独带一份：voiceAgeBand 是枚举供匹配，池名供 TTS 表演指令等展示场景
                voicePoolLabel = VoicePoolStore.poolLabelOf(profile),
                personality = profile.personality,
                updatedAt = profile.updatedAt,
            )
        }
        val characterRevision = buildString {
            append(LocalCharacterSpeakerResolver.VERSION)
            append(':')
            append(SpeechIdentity.characterRevision(characters))
        }
        // 那一份分段不是本轮这套朗读单元切出来的（改过划分方式或整页/整段视图不同）：
        // 段上的 paragraphIndex/start/end 是当时那一份的**段内相对坐标**，拿本轮的单元列表去查
        // 会查到别的句子上，重认一次等于把归属改错。用户没点「重新分析」就不动它，
        // 音色照样在这一趟现算（characterPerformances 照常带出去给选音）。
        if (!analysisResult.paragraphSpaceMatches) {
            return analysisResult.copy(characterPerformances = characterPerformances)
        }
        if (analysisResult.analysis.characterRevision == characterRevision) {
            return analysisResult.copy(
                fromCache = true,
                characterPerformances = characterPerformances,
            )
        }

        val resetSegments = analysisResult.segments.map { segment ->
            if (segment.source == SpeechResolutionSource.Local && !segment.userLocked) {
                segment.copy(
                    characterId = null,
                    characterName = "",
                    confidence = minOf(segment.confidence, 0.72f),
                    source = SpeechResolutionSource.Rule,
                )
            } else {
                segment
            }
        }
        val resolved = LocalCharacterSpeakerResolver.resolve(
            paragraphs = paragraphs,
            segments = resetSegments,
            characters = characters,
        )
        val status = if (resolved.any { it.needsSpeakerResolution }) {
            SpeechAnalysisStatus.Partial
        } else {
            SpeechAnalysisStatus.Success
        }
        val analysis = analysisResult.analysis.copy(
            characterRevision = characterRevision,
            status = status,
            updatedAt = now,
        )
        chapterSpeechGateway.saveAnalysis(analysis, resolved)
        return ChapterSpeechAnalysisResult(
            analysis = analysis,
            segments = resolved,
            fromCache = false,
            characterPerformances = characterPerformances,
        )
    }

    private val io.legado.app.domain.model.readaloud.ChapterSpeechSegment.needsSpeakerResolution: Boolean
        get() = characterId == null &&
            (roleType == SpeechRoleType.Character || roleType == SpeechRoleType.Thought)
}
