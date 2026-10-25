package io.legado.app.ui.book.readaloud.storyboard

import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

@Stable
data class SpeechStoryboardUiState(
    val bookUrl: String,
    val isLoading: Boolean = true,
    val multiSpeakerEnabled: Boolean = true,
    /** 这一章**实际**用的分析档（storageValue）；空 = 还没加载出章节。不是设置里那一档。 */
    val analysisMode: String = "",
    /** 已经分析过的章节，另外总会带上正在读的那一章 */
    val chapters: ImmutableList<StoryboardChapterUi> = persistentListOf(),
    /** 章节列表批量管理模式 */
    val isManaging: Boolean = false,
    val selectedChapterIndexes: ImmutableSet<Int> = persistentSetOf(),
    /** 正在看细节的章节序号，null 时展示章节列表 */
    val selectedChapterIndex: Int? = null,
    val chapterTitle: String = "",
    /** 细节页这一章是不是正在读的那一章：只有它能重新分析 */
    val isCurrentChapter: Boolean = false,
    /** 这一章的场景分组；没有场景数据时只有一个「未分组」占位组，列表按扁平渲染 */
    val scenes: ImmutableList<StoryboardSceneUi> = persistentListOf(),
    val summary: StoryboardSummaryUi? = null,
    /** 正在合成 / 试听中的片段 id */
    val previewingItemId: String? = null,
    /**
     * 「改归属」弹窗正在改的那一段 id；null = 弹窗没开。
     *
     * 开合放在状态里而不是页面 `remember`，因为候选名单要经
     * [io.legado.app.domain.gateway.BookKnowledgeGateway] 查（页面不能查库），
     * 与 [speakerOptions] 同生同灭。
     */
    val assigningItemId: String? = null,
    /** 改归属的候选：旁白 + 这本书启用/临时的人物，随本章一起加载 */
    val speakerOptions: ImmutableList<StoryboardSpeakerUi> = persistentListOf(),
    /**
     * 细节页正在播报的章内位置，-1 表示没在播。
     *
     * 用来高亮"当前正在念的这一条"。播放位置由朗读服务上报，与分镜条目按
     * [StoryboardItemUi.chapterPosition] 的区间命中——分配表覆盖会把一个分析段再切一刀，
     * 所以一条分镜行可能对应多条实际播放单元，按区间而不是按等值判断。
     */
    val playingChapterPosition: Int = -1,
    /**
     * 当前正在念的那一句**实际发声音色**（运行时结算后），空 = 没在播或后端未上报。
     *
     * 分镜行平时显示的是覆盖后的**计划**音色；音色路由与引擎拒绝都发生在发声瞬间
     * （见 [io.legado.app.domain.model.readaloud.SpeechVoiceOutcome]），只有正在播的那句
     * 才知道结算值。所以它只在当前行覆盖 [StoryboardItemUi.voiceName]，其余行保持计划音色。
     */
    val playingVoiceLabel: String = "",
    /** 批量补分析在跑：跑批期间标题栏那颗按钮变成「停止」 */
    val batchRunning: Boolean = false,
    /** 已跑完几章（含跳过与失败），与 [batchTotal] 一起显示进度 */
    val batchDone: Int = 0,
    /** 这一批一共几章；0 = 没在跑批 */
    val batchTotal: Int = 0,
)

@Stable
data class StoryboardChapterUi(
    val chapterIndex: Int,
    val title: String,
    val segmentCount: Int,
    val characterCount: Int,
    /** 正在读的那一章 */
    val isCurrent: Boolean = false,
)

@Stable
data class StoryboardSummaryUi(
    /** 0 表示这一章没有场景分组 */
    val sceneCount: Int,
    val segmentCount: Int,
    val dialogueCount: Int,
    val personCount: Int,
)

@Stable
data class StoryboardSceneUi(
    /** 从 1 开始的场景序号；0 表示未分组占位组 */
    val sceneNumber: Int,
    /** AI 起的短标题，可能为空 */
    val title: String,
    val items: ImmutableList<StoryboardItemUi> = persistentListOf(),
)

@Stable
data class StoryboardItemUi(
    val id: String,
    val text: String,
    val role: StoryboardRole,
    /** 角色名，旁白 / 未知说话人时为空 */
    val speakerName: String = "",
    val voiceName: String = "",
    val emotion: String = "",
    /** 章内段落序号，从 0 开始 */
    val paragraphIndex: Int = 0,
    /** 这条在正文里的起点；播放位置按 [chapterPosition, chapterPosition + text.length) 命中 */
    val chapterPosition: Int = 0,
    val confidence: Float = 0f,
    val source: StoryboardSource = StoryboardSource.Rule,
    /** 能不能试听：音色解析不出来时禁用 */
    val previewable: Boolean = false,
    /** 所属场景序号，从 1 开始；0 表示没有场景数据 */
    val sceneIndex: Int = 0,
    /** 所属场景短标题，可能为空 */
    val sceneTitle: String = "",
)

/**
 * 「改归属」弹窗里的一个候选说话人。
 *
 * 候选就是**这一章能选的人**：旁白那一档 + 这本书启用中的人物，外加 AI 认出来但还没进
 * 配音列表的临时人物（[isDraft]，只在行尾标一下——他们本来就能绑音色、参与路由，
 * 不需要先转正才能被点到）。停用的（从配音列表删掉的那批）不进候选。
 */
@Stable
data class StoryboardSpeakerUi(
    /** `book_character_profiles` 那一行的 id，也就是朗读侧查音色绑定用的主体 id */
    val characterId: String,
    val name: String,
    /** 旁白那一档：选中它等于「这一段没有说话人」 */
    val isNarrator: Boolean = false,
    /** 临时人物（AI 识别出的草稿档案） */
    val isDraft: Boolean = false,
    /** 这个人所属的声音池，空 = 没归池 */
    val poolLabel: String = "",
)

enum class StoryboardRole {
    Narrator,
    Character,
    Thought,
    Unknown,
}

/**
 * 这一条在正文里覆盖的区间（半开区间，含 [chapterPosition] 不含右端）。
 *
 * 分配表覆盖会把一个分析段再切成几条实际播放单元，所以"正在念的是哪一条"只能按区间
 * 命中，不能拿位置做等值比较。
 */
fun StoryboardItemUi.playRange(): IntRange =
    chapterPosition until (chapterPosition + text.length.coerceAtLeast(1))

/** 这段的判定来源，对应 domain 的 SpeechResolutionSource，UI 不 import 领域枚举 */
enum class StoryboardSource {
    Rule,
    Local,
    Ai,
    User,
    Fallback,
}

sealed interface SpeechStoryboardIntent {
    data object Refresh : SpeechStoryboardIntent

    /**
     * 丢掉这一章的分析缓存重新跑一遍，改了角色卡 / 分析模式 / 归因口径后用。
     *
     * 不再只对正在读的那一章有效：段落来源已经从"屏幕上的章节输入窗口"扩到"本地正文重建"
     * （见 [SpeechStoryboardViewModel]），下载过正文的章都能重跑。正文还没下载的章返回空列表。
     */
    data object Reanalyze : SpeechStoryboardIntent

    data class OpenChapter(val chapterIndex: Int) : SpeechStoryboardIntent

    data object BackToChapters : SpeechStoryboardIntent

    /** 点片段右侧试听：正在试听同一段则停止 */
    data class PreviewSegment(val itemId: String) : SpeechStoryboardIntent

    /**
     * 打开「改归属」（[itemId] 就是 `chapter_speech_segments` 那一行的 id）。
     *
     * 候选名单随本章一起加载（见 [SpeechStoryboardUiState.speakerOptions]），点开就是完整的，
     * 不等查询回来再填——这一页是核对清单，弹层先空一下会像没点开。
     */
    data class OpenSpeakerPicker(val itemId: String) : SpeechStoryboardIntent

    /** 关掉「改归属」但什么都不改 */
    data object CloseSpeakerPicker : SpeechStoryboardIntent

    /**
     * 把这一段的说话人改成 [speaker]；[StoryboardSpeakerUi.isNarrator] 那一档是「还给旁白」。
     *
     * 一次点选 = 一次写入：真相表那一行连同这几句**已有**的分配行一起改，否则下一次准备这一章
     * 会被旧分配顶回去（口径见
     * [io.legado.app.help.readaloud.cast.CastSegmentFinalizer.reattribute]）。改过的段就此算用户
     * 定稿，重跑分析不再动它；要回 AI 那份原判断，用「重新分析本章」。
     */
    data class AssignSegmentSpeaker(
        val itemId: String,
        val speaker: StoryboardSpeakerUi,
    ) : SpeechStoryboardIntent

    data object StopPreview : SpeechStoryboardIntent

    /** UI 播放器自然播完，只用来清「试听中」状态 */
    data object PreviewFinished : SpeechStoryboardIntent

    data object EnterManagement : SpeechStoryboardIntent

    data object ExitManagement : SpeechStoryboardIntent

    data class ToggleChapterSelected(val chapterIndex: Int) : SpeechStoryboardIntent

    data object SelectAllChapters : SpeechStoryboardIntent

    data object DeleteSelectedChapters : SpeechStoryboardIntent

    /** 章节列表里删除单章的分析缓存 */
    data class DeleteChapter(val chapterIndex: Int) : SpeechStoryboardIntent

    /**
     * 把勾选的章逐章补上说话人分析——旧「AI 分配角色」弹窗里"选起止章跑一批"的落点。
     *
     * 与"重新分析"的区别是它**不重做已经有结果的章**（一个 token 都不花），
     * 所以拿它跑全书等于"续跑 + 补齐"：只有没跑成过的（含失败章）会真的发请求。
     * 改了划分方式 / 模型 / 提示词的章也算跑过，要按新口径重判得点「重新分析」。
     */
    data object AnalyzeSelectedChapters : SpeechStoryboardIntent

    /** 停止批量分析：只关掉后面还没开的章，在飞的那一章照样跑完落库 */
    data object CancelBatchAnalysis : SpeechStoryboardIntent
}

sealed interface SpeechStoryboardEffect {
    data class ShowToast(val message: String) : SpeechStoryboardEffect

    /** 把已合成好的试听文件交给界面的 MediaPlayer 播放 */
    data class PlayPreview(val path: String) : SpeechStoryboardEffect

    data object StopPreviewPlayback : SpeechStoryboardEffect
}
