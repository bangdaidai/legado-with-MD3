package io.legado.app.ui.book.readaloud.storyboard

import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class SpeechStoryboardUiState(
    val bookUrl: String,
    val isLoading: Boolean = true,
    val multiSpeakerEnabled: Boolean = true,
    val analysisMode: String = "",
    /** 已经分析过的章节，另外总会带上正在读的那一章 */
    val chapters: ImmutableList<StoryboardChapterUi> = persistentListOf(),
    /** 正在看细节的章节序号，null 时展示章节列表 */
    val selectedChapterIndex: Int? = null,
    val chapterTitle: String = "",
    /** 细节页这一章是不是正在读的那一章：只有它能重新分析 */
    val isCurrentChapter: Boolean = false,
    val items: ImmutableList<StoryboardItemUi> = persistentListOf(),
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
data class StoryboardItemUi(
    val id: String,
    val text: String,
    val role: StoryboardRole,
    /** 角色名，旁白 / 未知说话人时为空 */
    val speakerName: String = "",
    val voiceName: String = "",
    val emotion: String = "",
)

enum class StoryboardRole {
    Narrator,
    Character,
    Thought,
    Unknown,
}

sealed interface SpeechStoryboardIntent {
    data object Refresh : SpeechStoryboardIntent

    /** 丢掉这一章的分析缓存重新跑一遍，改了角色卡 / 分析模式后用；只对正在读的那一章有效 */
    data object Reanalyze : SpeechStoryboardIntent

    data class OpenChapter(val chapterIndex: Int) : SpeechStoryboardIntent

    data object BackToChapters : SpeechStoryboardIntent
}

sealed interface SpeechStoryboardEffect {
    data class ShowToast(val message: String) : SpeechStoryboardEffect
}
