package io.legado.app.domain.model.readaloud

data class SpeechEngineRoute(
    val engineType: String,
    val engineId: String,
    val speakerId: String = "",
)

/**
 * 选出一条具体播放后端能执行的音色，并把"计划"与"实际"的差异一并算出来。
 *
 * 只做能力过滤（引擎类型、启用/可用/未排除），不做目录校验：音色在引擎里到底
 * 叫什么、setVoice 收不收，要等播放后端真的把它送进引擎才知道，那部分由后端补报。
 */
object SpeechVoiceRouter {

    fun route(
        cue: ReadAloudPlaybackCue,
        supportedEngineTypes: Set<String>,
        defaultRoute: SpeechEngineRoute,
        isExecutable: (ReadAloudVoice) -> Boolean = { true },
    ): SpeechVoiceOutcome {
        val planned = cue.voice
        val candidates = buildList {
            add(planned)
            addAll(cue.fallbackVoices)
        }.filterNotNull()
            .filter {
                it.enabled && it.available && !it.excluded &&
                    it.engineType in supportedEngineTypes
            }
            .distinctBy(ReadAloudVoice::id)

        val selected = candidates.firstOrNull(isExecutable)
        if (selected != null) {
            val sameAsPlanned = selected.id == planned?.id
            return SpeechVoiceOutcome(
                plannedVoice = planned,
                effectiveVoice = selected,
                status = if (sameAsPlanned) {
                    SpeechVoiceStatus.Applied
                } else {
                    SpeechVoiceStatus.FellBack
                },
                reason = if (sameAsPlanned) "" else fallbackReason(planned, selected),
            )
        }
        return SpeechVoiceOutcome(
            plannedVoice = planned,
            effectiveVoice = runtimeDefault(defaultRoute),
            status = if (planned == null) {
                // 本来就没指定音色，用引擎默认声正是预期结果，不算差异
                SpeechVoiceStatus.Applied
            } else if (planned.engineType !in supportedEngineTypes) {
                SpeechVoiceStatus.EngineMismatch
            } else {
                SpeechVoiceStatus.EngineDefault
            },
            reason = emptyReason(planned, supportedEngineTypes),
        )
    }

    /** 运行时兜底音色：不落库，只在这一句用一次。speakerId 为空表示"引擎自己的默认声"。 */
    private fun runtimeDefault(route: SpeechEngineRoute) = ReadAloudVoice(
        id = "runtime-default:${route.engineType}:${route.engineId}:${route.speakerId}",
        engineType = route.engineType,
        engineId = route.engineId,
        speakerId = route.speakerId,
        displayName = "",
        managedBy = ReadAloudVoice.MANAGED_BY_USER,
    )

    private fun fallbackReason(planned: ReadAloudVoice?, selected: ReadAloudVoice): String {
        if (planned == null) return "没有指定音色，已改用「${selected.voiceLabel}」"
        val why = when {
            planned.engineType != selected.engineType -> "所属引擎不同"
            !planned.enabled -> "已被停用"
            !planned.available -> "当前不可用"
            planned.excluded -> "已被排除"
            else -> "不可执行"
        }
        return "「${planned.voiceLabel}」$why，已改用「${selected.voiceLabel}」"
    }

    private fun emptyReason(planned: ReadAloudVoice?, supportedEngineTypes: Set<String>): String {
        if (planned == null) return ""
        val why = when {
            planned.engineType !in supportedEngineTypes -> "当前引擎执行不了它所属的引擎类型"
            !planned.enabled -> "已被停用"
            !planned.available -> "当前不可用"
            planned.excluded -> "已被排除"
            else -> "没有可用音色"
        }
        return "「${planned.voiceLabel}」$why，已改用引擎默认声"
    }
}