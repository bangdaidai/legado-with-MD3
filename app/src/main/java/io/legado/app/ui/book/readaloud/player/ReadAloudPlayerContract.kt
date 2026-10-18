package io.legado.app.ui.book.readaloud.player

import androidx.compose.runtime.Stable
import io.legado.app.domain.model.readaloud.SpeechVoiceStatus
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
    /**
     * 实际发声的音色标签（只含音色名），播放后端上报；为空表示还没发声或没有指定音色。
     *
     * 显示的是实际值而不是计划值，理由见 [ReadAloudPlayerSourceState.voiceLabel]。
     */
    val voiceLabel: String = "",
    val voiceStatus: SpeechVoiceStatus = SpeechVoiceStatus.Applied,
    val voiceStatusReason: String = "",

    /** 实际音色与计划音色不一致；界面据此把音色卡标红并展开原因。 */
    val hasVoiceMismatch: Boolean get() = !voiceStatus.isApplied,

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
    /** 正文字号（sp），跟随阅读页当前排版样式；随 ReadStyleGateway 的 revision 重建。 */
    val bodyTextSize: Int = 20,
    /** 背景音乐音量（0f..1f）；只影响配乐轨，不改朗读本身的音量。 */
    val bgmVolume: Float = 1f,
)

sealed interface ReadAloudPlayerSheet {
    data object Speed : ReadAloudPlayerSheet
    data object Timer : ReadAloudPlayerSheet

    /** 背景音乐音量：配乐是朗读的第二条轨，音量独立于语速/定时。 */
    data object BgmVolume : ReadAloudPlayerSheet
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
    /** 背景音乐音量（0f..1f）；写 prefs 并立即对配乐轨生效，不碰朗读分页。 */
    data class SetBgmVolume(val value: Float) : ReadAloudPlayerIntent

    /** 切换定时模式；切到分钟模式会清掉章节配额，反之亦然。 */
    data class SetTimerMode(val value: String) : ReadAloudPlayerIntent

    /** 章节定时剩余章数；0 表示关闭章节定时。 */
    data class SetTimerChapters(val value: Int) : ReadAloudPlayerIntent

    /** 分钟定时到点后是否读完本章再停。 */
    data class SetFinishCurrentChapterAfterTimer(val value: Boolean) : ReadAloudPlayerIntent
    data class OpenSheet(val sheet: ReadAloudPlayerSheet) : ReadAloudPlayerIntent
    data object DismissSheet : ReadAloudPlayerIntent
    /** 页面退场（返回阅读页）：请求阅读页在跟随状态下立即把页面追到声音位置。 */
    data object LeavingToReader : ReadAloudPlayerIntent
    data class SeekTo(val chapterPosition: Int) : ReadAloudPlayerIntent

    /**
     * 点音色卡：去人物配音分配页改当前这个角色的音色。
     *
     * 过去这张卡不可点，换音色只能绕「设置 → 引擎与音色 → 长按音色行」，
     * 而那里管的是全局默认旁白，改不到正在念的这个角色。
     */
    data object OpenVoiceCasting : ReadAloudPlayerIntent
}

sealed interface ReadAloudPlayerEffect {
    /** 返回经典阅读界面并自动打开剧本识别（有声书）；bookUrl 为空表示没有可打开的书。 */
    data class ReturnToClassic(val bookUrl: String) : ReadAloudPlayerEffect

    // 听书设置入口页，与设置页的目录目的不同但在同一入口内。
    data class OpenEnginesAndVoices(val bookUrl: String?) : ReadAloudPlayerEffect
    data object OpenTtsCache : ReadAloudPlayerEffect
    data class OpenBookVoiceCasting(val bookUrl: String) : ReadAloudPlayerEffect
    data class OpenSpeechStoryboard(val bookUrl: String) : ReadAloudPlayerEffect

    /** 角色声音池 / 背景音乐池；与设置里的朗读设置 hub 是同一批 Nav3 目的地。 */
    data object OpenVoicePool : ReadAloudPlayerEffect
    data object OpenBgmPool : ReadAloudPlayerEffect

    data object OpenSystemTtsSettings : ReadAloudPlayerEffect

    /** 听书音频缓存清空完成（用于界面提示；成功后由宿主显示成功的文案） */
    data object TtsCacheCleared : ReadAloudPlayerEffect

    /** 切换角色管理对话（多角色模式）但没有可用 AI 模型，用于提示或拒绝切换。 */
    data object SpeechAnalysisAiModelRequired : ReadAloudPlayerEffect
}
