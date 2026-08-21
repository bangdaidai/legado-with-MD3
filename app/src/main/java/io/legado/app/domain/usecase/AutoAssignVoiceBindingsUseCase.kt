package io.legado.app.domain.usecase

import io.legado.app.constant.AppLog
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.BookVoiceBinding
import io.legado.app.domain.model.readaloud.CharacterPerformanceProfile
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.help.readaloud.HttpTtsVoiceCatalog

/**
 * 给本章真正有台词、却还没有音色的角色自动挑一个音色，落成 [BookVoiceBinding.SOURCE_AUTO] 绑定。
 *
 * 绑定策略：
 * - 用户手动绑的、导入的、或 locked 的绑定永不覆盖；
 * - 自动绑定只在音色失效（被删、停用、不可用）时重挑，平时不重复评估；
 * - 只有能判断角色性别时才分配，判断不出来就交给原来的性别兜底链，不瞎猜。
 */
class AutoAssignVoiceBindingsUseCase(
    private val voiceGateway: ReadAloudVoiceGateway,
) {

    suspend operator fun invoke(
        bookUrl: String,
        performances: List<CharacterPerformanceProfile>,
        now: Long = System.currentTimeMillis(),
    ): List<BookVoiceBinding> {
        if (bookUrl.isEmpty() || performances.isEmpty()) return emptyList()
        val voices = voiceGateway.getEnabledVoices().filter { it.available }
        if (voices.isEmpty()) return emptyList()
        val bindings = voiceGateway.getBindings(bookUrl)
        val usableVoiceIds = voices.mapTo(hashSetOf(), ReadAloudVoice::id)
        val bindingsBySubject = bindings.associateBy { it.subjectType to it.subjectId }
        // 已被其它主体占用的音色尽量不重复分配，免得两个角色一个声音
        val takenVoiceIds = bindings.filter { it.voiceId in usableVoiceIds }
            .mapTo(hashSetOf(), BookVoiceBinding::voiceId)
        val preferredEngine = preferredEngineType(bindings, voices)
        val voicesByGender = voices.groupBy { it.gender() }

        val created = mutableListOf<Pair<BookVoiceBinding, String>>()
        performances.forEach { performance ->
            val subject = BookVoiceBinding.SUBJECT_CHARACTER to performance.characterId
            val existing = bindingsBySubject[subject]
            if (!existing.needsAutoAssignment(usableVoiceIds)) return@forEach
            val gender = performance.gender() ?: return@forEach
            val voice = pick(voicesByGender[gender].orEmpty(), takenVoiceIds, preferredEngine)
                ?: return@forEach
            takenVoiceIds += voice.id
            val binding = BookVoiceBinding(
                bookUrl = bookUrl,
                subjectType = BookVoiceBinding.SUBJECT_CHARACTER,
                subjectId = performance.characterId,
                voiceId = voice.id,
                locked = false,
                source = BookVoiceBinding.SOURCE_AUTO,
                confidence = if (performance.voiceGender == gender) {
                    CONFIDENCE_EXPLICIT
                } else {
                    CONFIDENCE_INFERRED
                },
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
            voiceGateway.upsertBinding(binding)
            created += binding to voice.displayName
        }
        if (created.isNotEmpty()) {
            AppLog.putDebug(
                "自动分配音色 ${created.size} 个角色：" +
                    created.joinToString { (binding, name) -> "$name(${binding.confidence})" }
            )
        }
        return created.map { it.first }
    }

    private fun BookVoiceBinding?.needsAutoAssignment(usableVoiceIds: Set<String>): Boolean {
        this ?: return true
        if (locked || source != BookVoiceBinding.SOURCE_AUTO) return false
        return voiceId !in usableVoiceIds
    }

    /** 只有性别明确才自动分配：角色卡写的性别优先，其次男主/女主这类定位 */
    private fun CharacterPerformanceProfile.gender(): String? = when (voiceGender) {
        GENDER_MALE, GENDER_FEMALE -> voiceGender
        else -> when (role) {
            BookVoiceBinding.SUBJECT_MALE_LEAD,
            BookVoiceBinding.SUBJECT_MALE_SUPPORTING -> GENDER_MALE

            BookVoiceBinding.SUBJECT_FEMALE_LEAD,
            BookVoiceBinding.SUBJECT_FEMALE_SUPPORTING -> GENDER_FEMALE

            else -> null
        }
    }

    /**
     * 音色表本身没有性别字段：http 音色能从脚本 `voices()` 声明的 gender 拿到，
     * 系统/云端只能看名字。Azure/Edge 的中文音色是「晓=女、云=男」。
     * 认不出来就返回 unknown，这类音色不参与自动分配。
     */
    private fun ReadAloudVoice.gender(): String {
        if (engineType == ReadAloudVoice.ENGINE_HTTP) {
            val declared = HttpTtsVoiceCatalog.fromVoice(this)?.gender?.trim()?.lowercase()
            if (!declared.isNullOrEmpty()) {
                when {
                    declared.startsWith("f") || declared.startsWith("w") || "女" in declared ->
                        return GENDER_FEMALE

                    declared.startsWith("m") || "男" in declared -> return GENDER_MALE
                }
            }
        }
        val name = displayName.lowercase()
        // female 里含 male、woman 里含 man，必须先判女再判男
        return when {
            FEMALE_HINTS.any { it in name } -> GENDER_FEMALE
            MALE_HINTS.any { it in name } -> GENDER_MALE
            else -> GENDER_UNKNOWN
        }
    }

    private fun pick(
        candidates: List<ReadAloudVoice>,
        taken: Set<String>,
        preferredEngine: String,
    ): ReadAloudVoice? {
        if (candidates.isEmpty()) return null
        // 同性别音色全被占完了就允许复用，几个角色共用总比整本掉回兜底音好
        val pool = candidates.filterNot { it.id in taken }.ifEmpty { candidates }
        return pool.minWithOrNull(
            compareByDescending<ReadAloudVoice> { it.engineType == preferredEngine }
                .thenBy(ReadAloudVoice::displayName)
                .thenBy(ReadAloudVoice::id)
        )
    }

    /** 跟随这本书已有绑定的引擎，一本书里混引擎会让协调器来回切换 */
    private fun preferredEngineType(
        bindings: List<BookVoiceBinding>,
        voices: List<ReadAloudVoice>,
    ): String {
        val voicesById = voices.associateBy(ReadAloudVoice::id)
        return bindings.mapNotNull { voicesById[it.voiceId]?.engineType }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: voices.groupingBy(ReadAloudVoice::engineType).eachCount()
                .maxByOrNull { it.value }?.key
            ?: ReadAloudVoice.ENGINE_SYSTEM
    }
}

private const val GENDER_MALE = "male"
private const val GENDER_FEMALE = "female"
private const val GENDER_UNKNOWN = "unknown"

/** 性别是角色卡里明确写的 */
private const val CONFIDENCE_EXPLICIT = 0.85f

/** 性别是从男主/女主这类定位推出来的 */
private const val CONFIDENCE_INFERRED = 0.7f

private val FEMALE_HINTS = listOf("female", "woman", "girl", "女", "晓", "xiao", "姐", "妹", "娘")
private val MALE_HINTS = listOf("male", "man", "boy", "男", "云", "yun", "哥", "叔", "爷")
