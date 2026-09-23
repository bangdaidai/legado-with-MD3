package io.legado.app.ui.book.readaloud.player

import androidx.compose.runtime.Stable
import io.legado.app.domain.model.settings.ReadAloudTimerMode
import io.legado.app.ui.widget.components.player.PlayerChapterUi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class ReadAloudTextLineUi(
    val text: String,
    val chapterPosition: Int,
)

@Stable
data class ReadAloudPlayerUiState(
    val bookUrl: String = "",
    val bookName: String = "",
    val author: String = "",
    val coverPath: String? = null,
    val sourceOrigin: String? = null,
    val chapterIndex: Int = -1,
    val chapterTitle: String = "",
    val chapters: ImmutableList<PlayerChapterUi> = persistentListOf(),
    val chapterText: String = "",
    val textLines: ImmutableList<ReadAloudTextLineUi> = persistentListOf(),
    val activeTextLine: Int = -1,
    val currentText: String = "",
    val nextText: String = "",
    val chapterPosition: Int = 0,
    val chapterLength: Int = 1,
    val engineName: String = "",
    val speakerName: String = "",
    val isPaused: Boolean = false,
    val isPreparing: Boolean = false,
    /**
     * 朗读服务是否在跑（`BaseReadAloudService.isRun`）。
     *
     * 全局胶囊的显隐以它为准，而不是 `ReadAloudSessionStore.status`：status 只在
     * play/pause/stop 事件里更新，服务仍在跑时可能停在旧值。
     */
    val readAloudRunning: Boolean = false,
    val speed: Int = 10,
    val timerMinutes: Int = 0,
    /** 分钟定时到点后读完本章再停；只对分钟模式有意义。 */
    val finishCurrentChapterAfterTimer: Boolean = false,
    /** 定时模式：分钟 / 章节；两者互斥。 */
    val timerMode: String = ReadAloudTimerMode.Minute.storageValue,
    /** 章节定时剩余章数；0 表示未开启。 */
    val timerChapters: Int = 0,
    val bgMode: Int = 0,
    val activeSheet: ReadAloudPlayerSheet? = null,
)

sealed interface ReadAloudPlayerSheet {
    data object Speed : ReadAloudPlayerSheet
    data object Timer : ReadAloudPlayerSheet
}

sealed interface ReadAloudPlayerIntent {
    data object Refresh : ReadAloudPlayerIntent
    data object TogglePause : ReadAloudPlayerIntent

    /** 悬浮胶囊上的停止按钮与经典控制面板同义。 */
    data object StopReadAloud : ReadAloudPlayerIntent
    data object PreviousChapter : ReadAloudPlayerIntent
    data object NextChapter : ReadAloudPlayerIntent
    data object PreviousParagraph : ReadAloudPlayerIntent
    data object NextParagraph : ReadAloudPlayerIntent
    /** 切回阅读界面的经典朗读控制面板。 */
    data object SwitchToClassic : ReadAloudPlayerIntent
    data object CycleBgMode : ReadAloudPlayerIntent
    data class SelectChapter(val index: Int) : ReadAloudPlayerIntent
    data class SetBgMode(val value: Int) : ReadAloudPlayerIntent
    data class SetSpeed(val value: Int) : ReadAloudPlayerIntent
    data class SetTimer(val minutes: Int) : ReadAloudPlayerIntent

    /** 切换定时模式；切到分钟模式会清掉章节配额，反之亦然。 */
    data class SetTimerMode(val value: String) : ReadAloudPlayerIntent

    /** 章节定时剩余章数；0 表示关闭章节定时。 */
    data class SetTimerChapters(val value: Int) : ReadAloudPlayerIntent

    /** 分钟定时到点后是否读完本章再停。 */
    data class SetFinishCurrentChapterAfterTimer(val value: Boolean) : ReadAloudPlayerIntent
    data class OpenSheet(val sheet: ReadAloudPlayerSheet) : ReadAloudPlayerIntent
    data object DismissSheet : ReadAloudPlayerIntent
    data class SeekTo(val chapterPosition: Int) : ReadAloudPlayerIntent
}

sealed interface ReadAloudPlayerEffect {
    /** 跳回阅读界面并自动打开经典朗读控制面板；bookUrl 为空表示没有可打开的书。 */
    data class ReturnToClassic(val bookUrl: String) : ReadAloudPlayerEffect

    // 朗读设置整页跳独立整页的目的；与阅读器宿主同一批入口。
    data class OpenEnginesAndVoices(val bookUrl: String?) : ReadAloudPlayerEffect
    data object OpenTtsCache : ReadAloudPlayerEffect
    data class OpenBookVoiceCasting(val bookUrl: String) : ReadAloudPlayerEffect
    data class OpenSpeechStoryboard(val bookUrl: String) : ReadAloudPlayerEffect
    data object OpenSystemTtsSettings : ReadAloudPlayerEffect

    /** 朗读音频缓存已清除；由设置页宿主提示成功文案。 */
    data object TtsCacheCleared : ReadAloudPlayerEffect

    /** 切换到非规则对话分析模式但没有可用 AI 模型，宿主提示后拒绝切换。 */
    data object SpeechAnalysisAiModelRequired : ReadAloudPlayerEffect
}
