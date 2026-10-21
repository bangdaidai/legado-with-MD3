package io.legado.app.domain.model.readaloud

/**
 * 虚拟说话人（对齐 legado_NG 的 virtualSpeakerName / 对白兜底）。
 *
 * AI 确认某段是人物声音、但说话人是「大汉」「侍卫」这类路人泛称时不建角色卡，
 * 只把性别用虚拟名承载在 segment.characterName 上：分镜页可见「对白男/对白女」，
 * 音色计划据此走对白兜底绑定（unknown_male / unknown_female）。
 */
const val SPEAKER_DIALOGUE_MALE_NAME = "对白男"
const val SPEAKER_DIALOGUE_FEMALE_NAME = "对白女"

/** 性别 → 虚拟说话人名；性别未知返回 null。 */
fun dialogueFallbackName(gender: String): String? = when (gender) {
    "male" -> SPEAKER_DIALOGUE_MALE_NAME
    "female" -> SPEAKER_DIALOGUE_FEMALE_NAME
    else -> null
}

/** 虚拟说话人名 → 性别；不是虚拟名返回 null。 */
fun dialogueFallbackGender(name: String): String? = when (name) {
    SPEAKER_DIALOGUE_MALE_NAME -> "male"
    SPEAKER_DIALOGUE_FEMALE_NAME -> "female"
    else -> null
}

data class ReadAloudVoice(
    val id: String,
    val engineType: String,
    val engineId: String,
    val speakerId: String,
    val displayName: String,
    val traitsJson: String = "[]",
    val emotionCatalogJson: String = "[]",
    val managedBy: String = MANAGED_BY_USER,
    val enabled: Boolean = true,
    val available: Boolean = true,
    /** 用户主动排除：不进候选、不参与选音，但行要留着，见实体上的说明。 */
    val excluded: Boolean = false,
    val revision: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /**
     * 只含音色名的短标签。
     *
     * [displayName] 对"一条引擎带多个音色"的来源是 `引擎名 · 音色名` 拼出来的
     * （见 `HttpTtsVoiceCatalog.catalogEntries`），不能直接当音色名用：传给书源脚本的
     * `currentSpeakerName` 是裸音色名，界面上紧凑位置（分镜行）也只放得下音色名。
     * 单一音色来源的 displayName 本来就没有引擎前缀，这里原样返回。
     */
    val voiceLabel: String get() = displayName.substringAfterLast(DISPLAY_NAME_SEPARATOR)

    /** displayName 里的引擎名部分；单一音色来源返回空串。 */
    val engineLabel: String get() = displayName.substringBeforeLast(
        DISPLAY_NAME_SEPARATOR,
        missingDelimiterValue = "",
    ).takeIf { it != displayName }.orEmpty()

    companion object {
        const val ENGINE_SYSTEM = "system"
        const val ENGINE_HTTP = "http"
        const val ENGINE_CLOUD = "cloud_tts"
        const val MANAGED_BY_USER = "user"
        const val MANAGED_BY_CONFIGURED_TTS = "configured_tts"

        /** [displayName] 里引擎名与音色名的分隔符，形态与列表里显示的一致。 */
        const val DISPLAY_NAME_SEPARATOR = " · "

        /**
         * 自动同步来的音色被用户改过名字：条目仍由引擎目录维护，但 [displayName] 归用户。
         *
         * [io.legado.app.domain.usecase.SyncReadAloudVoicesUseCase] 遇到这个值时保留旧名字。
         */
        const val MANAGED_BY_USER_NAMED = "configured_tts_renamed"

        /** 由引擎目录同步维护的条目（含被改过名的） */
        val CATALOG_MANAGED = setOf(MANAGED_BY_CONFIGURED_TTS, MANAGED_BY_USER_NAMED)

        /**
         * 源自带目录：从 httpTTS 源的 `speakersJson` / `emotionsJson` 导入的音色。
         *
         * 单独一个取值，是为了不和 [MANAGED_BY_CONFIGURED_TTS] 混在一次回收里——
         * 后者每次同步都以「当前引擎列表」为全集，会把源自带的目录行整批删掉。
         */
        const val MANAGED_BY_HTTP_SOURCE_CATALOG = "http_source_catalog"
    }
}

data class ReadAloudEngineSelection(
    val engineType: String,
    val engineId: String,
    val speakerId: String = "",
    val displayName: String = "",
)

data class VoiceCatalogEntry(
    val engineType: String,
    val engineId: String,
    val speakerId: String = "",
    val displayName: String,
    val traitsJson: String = "[]",
    val emotionCatalogJson: String = "[]",
    val sourceRevision: Long = 0L,
    val managedBy: String = ReadAloudVoice.MANAGED_BY_CONFIGURED_TTS,
)

data class BookVoiceBinding(
    val bookUrl: String,
    val subjectType: String,
    val subjectId: String,
    val voiceId: String,
    val locked: Boolean = false,
    val source: String = SOURCE_USER,
    val confidence: Float = 1f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val SUBJECT_CHARACTER = "character"
        const val SUBJECT_NARRATOR = "narrator"
        const val SUBJECT_UNKNOWN_MALE = "unknown_male"
        const val SUBJECT_UNKNOWN_FEMALE = "unknown_female"
        const val SUBJECT_MALE_LEAD = "male_lead"
        const val SUBJECT_FEMALE_LEAD = "female_lead"
        const val SUBJECT_MALE_SUPPORTING = "male_supporting"
        const val SUBJECT_FEMALE_SUPPORTING = "female_supporting"
        const val SUBJECT_UNKNOWN = "unknown"

        const val SOURCE_USER = "user"
        const val SOURCE_AUTO = "auto"
        const val SOURCE_IMPORT = "import"
    }
}

data class ChapterSpeechAnalysis(
    val id: String,
    val bookUrl: String,
    val chapterIndex: Int,
    val contentHash: String,
    val resolverVersion: String,
    val characterRevision: String,
    val status: SpeechAnalysisStatus,
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

data class ChapterSpeechSegment(
    val id: String,
    val analysisId: String,
    val bookUrl: String,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val start: Int,
    val end: Int,
    val chapterPosition: Int,
    val text: String,
    val roleType: SpeechRoleType,
    val characterId: String? = null,
    val characterName: String = "",
    val emotion: String = "",
    /**
     * 只作用于这一段的变声器预设名（正文胶囊那一栏设的），空 = 跟随角色全局。
     *
     * 由 `CastSpeechOverlay` 从分配表抄进播放单元：朗读侧只拿得到单元，
     * 让它带着名字下来就不用再去猜「这句是章内第几个引号」。
     */
    val voiceEffect: String = "",
    val confidence: Float = 0f,
    val source: SpeechResolutionSource,
    val userLocked: Boolean = false,
    /** 场景序号，从 1 开始；0 表示还没有场景分组 */
    val sceneIndex: Int = 0,
    /** AI 给场景起的短标题，可能为空 */
    val sceneTitle: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 一本书里某一章已存下来的分镜统计，用于分镜章节列表。
 *
 * 这里没有「是不是当前设置生成的」那一档：库里那一份就是这一章的结果，改划分方式 /
 * 分析模式 / 模型 / 提示词 / 归因口径都不会让它作废（见
 * [io.legado.app.domain.usecase.AnalyzeChapterSpeechUseCase]），所以列表也无从分出现用与不用。
 */
data class ChapterSpeechSummary(
    val chapterIndex: Int,
    /** 目录里的章节标题，目录被清过时为空 */
    val title: String,
    val segmentCount: Int,
    val characterCount: Int,
    val updatedAt: Long,
)

data class CanonicalSpeechParagraph(
    val index: Int,
    val text: String,
    val chapterPosition: Int,
)

data class SpeechSegmentDraft(
    val paragraphIndex: Int,
    val start: Int,
    val end: Int,
    val chapterPosition: Int,
    val text: String,
    val roleType: SpeechRoleType,
    val confidence: Float,
    val emotion: String = "",
    val source: SpeechResolutionSource = SpeechResolutionSource.Rule,
)

data class SpeechPlanItem(
    val segment: ChapterSpeechSegment,
    val voice: ReadAloudVoice?,
    val fallbackVoices: List<ReadAloudVoice>,
    val characterPerformance: CharacterPerformanceProfile? = null,
    /**
     * 正则角色「命中不念、改放音频」带下来的音效串，格式契约见
     * [io.legado.app.help.readaloud.cast.RegexCastSplitter] 底部（多条换行分隔、可带 `#千分位`）。
     * 生产方 CastSpeechOverlay，消费方 [ReadAloudPlaybackQueue.from] → BaseReadAloudService.takeCueSounds。
     * 空 = 不放。
     */
    val soundEffect: String = "",
)

data class ChapterSpeechAnalysisResult(
    val analysis: ChapterSpeechAnalysis,
    val segments: List<ChapterSpeechSegment>,
    val fromCache: Boolean,
    /**
     * 库里那一份分段与本轮这份朗读单元列表是不是**同一套划分切出来的**（比对 `contentHash`）。
     *
     * 换划分方式时它是 false：分段行上的 `paragraphIndex / start / end` 是当时那套单元的
     * **段内相对坐标**，拿本轮的单元列表去查会查到别的句子上，所以
     * [io.legado.app.domain.usecase.ResolveLocalSpeakersUseCase] 靠它决定要不要重认说话人。
     * 章内绝对坐标（`chapterPosition` + 原文）不受粒度影响，定稿回放与分配表覆盖照常用，
     * 它们用的是 [SpeechIdentity.alignsWithParagraphs] 那条与粒度无关的同源判据。
     */
    val paragraphSpaceMatches: Boolean = true,
    val characterPerformances: List<CharacterPerformanceProfile> = emptyList(),
)

data class CharacterPerformanceProfile(
    val characterId: String,
    val role: String = "",
    val voiceGender: String = "unknown",
    /** 结构化年龄段枚举（child/teen/young_adult/adult/elderly/unknown）。 */
    val voiceAgeBand: String = "unknown",
    /** 关联的声音池显示名（如「女青年」）；空串 = 未指定池。与 [voiceAgeBand] 分开： */
    /** 后者是枚举供匹配，前者是池名供 TTS 表演指令等展示场景使用。 */
    val voicePoolLabel: String = "",
    val personality: String = "",
    val updatedAt: Long = 0L,
)

enum class SpeechAnalysisMode(val storageValue: String) {
    Rule("rule"),
    RuleWithAi("rule_with_ai"),
    AiUnderstanding("ai_understanding");

    companion object {
        fun fromStorage(value: String): SpeechAnalysisMode =
            entries.firstOrNull { it.storageValue == value } ?: Rule
    }
}

/**
 * 归因口径：AI 拿不准一句话是谁说的时候该怎么办。
 *
 * 两套判定规则与输出契约完全共用，切换只换这一档的口径文本（见
 * [io.legado.app.domain.usecase.RefineSpeechWithAiUseCase]），所以解析器不会分裂。
 * 它改变发给模型的提示词，因此写进新分析的版本号里；**已经分析过的章不受影响**——
 * 那一档只管下一次真正跑分析时怎么问模型，要让它管到旧章就点「重新分析」。
 *
 * - [MustAnswer]（默认）：每一条都要给说话人，沉默、咳嗽、惨叫、人群齐声也算某个人发出的声音。
 *   漏分的代价是整句掉回兜底音，比错分更难归因。
 * - [EvidenceOnly]：只有明确证据才给，其余留空走默认音——宁可漏分，不可错分。
 */
enum class SpeechAttributionStrictness(val storageValue: String) {
    MustAnswer("must_answer"),
    EvidenceOnly("evidence_only");

    companion object {
        fun fromStorage(value: String): SpeechAttributionStrictness =
            entries.firstOrNull { it.storageValue == value } ?: MustAnswer
    }
}

data class SpeakerCharacter(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList(),
    val role: String = "",
    val voiceGender: String = "unknown",
    val voiceAgeBand: String = "unknown",
    val updatedAt: Long = 0L,
)

enum class SpeechRoleType(val storageValue: String) {
    Narrator("narrator"),
    Character("character"),
    Thought("thought"),
    Unknown("unknown");

    companion object {
        fun fromStorage(value: String): SpeechRoleType =
            entries.firstOrNull { it.storageValue == value } ?: Unknown
    }
}

enum class SpeechResolutionSource(val storageValue: String) {
    Rule("rule"),
    Local("local"),
    Ai("ai"),
    User("user"),
    Fallback("fallback");

    companion object {
        fun fromStorage(value: String): SpeechResolutionSource =
            entries.firstOrNull { it.storageValue == value } ?: Fallback
    }
}

enum class SpeechAnalysisStatus(val storageValue: String) {
    Pending("pending"),
    Running("running"),
    Success("success"),
    Partial("partial"),
    Failed("failed");

    companion object {
        fun fromStorage(value: String): SpeechAnalysisStatus =
            entries.firstOrNull { it.storageValue == value } ?: Failed
    }
}

/**
 * 一条朗读单元**实际发声**时的音色状态。
 *
 * 存在的理由：计划音色（`SpeechPlanItem.voice`）和真正送进引擎的音色不是同一个值。
 * 路由器会按引擎能力换掉它，引擎又可能拒绝执行它，两者过去都不回流到界面，
 * 于是听书页上写着一个音色名、耳朵里是另一个声音。播放后端把实际结果按本枚举
 * 上报（`BaseReadAloudService.reportEffectiveVoice`），界面据此显示，不再显示计划值。
 */
enum class SpeechVoiceStatus {
    /** 计划音色原样执行。 */
    Applied,

    /** 计划音色当前引擎执行不了，退到了候选链里的另一条音色。 */
    FellBack,

    /** 候选链全被过滤掉，只能用引擎出厂默认音发声。 */
    EngineDefault,

    /** 计划音色属于别的引擎类型（例如绑了云端音色却走系统直读）。 */
    EngineMismatch,

    /** 计划音色没记录具体音色（speakerId 为空），引擎会用它自己的默认声。 */
    NoSpeaker,

    /** 计划音色的名字在引擎目录里查不到（引擎升级改名、getVoices 整表为空等）。 */
    NotFound,

    /** 引擎拒绝了这次切换，已退回默认音。 */
    ApplyFailed,
    ;

    /** 是否真的按计划发出了声。界面只对 [Applied] 之外的取值给出提示。 */
    val isApplied: Boolean get() = this == Applied
}

/** 一次发声的音色结算结果：计划是什么、实际是什么、为什么不一样。 */
data class SpeechVoiceOutcome(
    val plannedVoice: ReadAloudVoice?,
    val effectiveVoice: ReadAloudVoice?,
    val status: SpeechVoiceStatus,
    val reason: String = "",
) {
    /** 界面展示用：实际生效音色的短标签，空表示引擎默认声。 */
    val effectiveLabel: String get() = effectiveVoice?.voiceLabel.orEmpty()

    /** 界面展示用：计划音色的短标签。 */
    val plannedLabel: String get() = plannedVoice?.voiceLabel.orEmpty()
}
