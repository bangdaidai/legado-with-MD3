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
    /** 用户手动改过、不再被 AI 覆盖 */
    val locked: Boolean = false,
    /** 能不能试听：音色解析不出来时禁用 */
    val previewable: Boolean = false,
    /** 所属场景序号，从 1 开始；0 表示没有场景数据 */
    val sceneIndex: Int = 0,
    /** 所属场景短标题，可能为空 */
    val sceneTitle: String = "",
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
     * 锁定 / 解锁这一段（[itemId] 就是 `chapter_speech_segments` 那一行的 id）。
     *
     * 锁定 = 用户认可这一句的说话人：现成的「跳过锁定段」判断会让重新分析不再改它，
     * 这是正文胶囊之外唯一能把结论钉到分析表上的动作。它**不换音色、也不重启朗读**，
     * 所以只改那一列，界面就地翻转徽标即可。
     */
    data class ToggleSegmentLock(val itemId: String) : SpeechStoryboardIntent

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
