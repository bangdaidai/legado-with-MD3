package io.legado.app.ui.book.readaloud.player

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.ReadAloudBgMode
import io.legado.app.data.repository.ReadAloudSettingsRepository
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.gateway.ReadStyleGateway
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.AiTaskType
import io.legado.app.domain.model.readaloud.ReadAloudContentSplitSetting
import io.legado.app.domain.model.readaloud.ReadAloudSplitSymbol
import io.legado.app.domain.model.settings.ReadAloudSettings
import io.legado.app.domain.model.settings.ReadAloudTimerMode
import io.legado.app.domain.model.settings.ReadSettings
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.compatDsInt
import io.legado.app.model.ReadAloudSessionStore
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.widget.components.player.PlayerChapterUi
import io.legado.app.utils.TTSCacheUtils
import io.legado.app.utils.postEvent
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ReadAloudPlayerViewModel(
    private val coordinator: ReadAloudPlayerCoordinator,
    private val readAloudSettingsGateway: ReadAloudSettingsGateway,
    private val readSettingsGateway: ReadSettingsGateway,
    private val readStyleGateway: ReadStyleGateway,
    private val application: Application,
    private val readAloudSessionStore: ReadAloudSessionStore,
    private val aiProfileGateway: AiProfileGateway,
) : ViewModel() {

    private val activeSheet = MutableStateFlow<ReadAloudPlayerSheet?>(null)

    /**
     * 朗读设置快照。
     *
     * 听书播放界面是独立目的地，不依赖阅读器 ViewModel，所以设置直接从全局设置源投影；
     * 阅读界面里的配置卡片仍用 `ReadBookUiState`（内容相同，只是宿主不同）。
     */
    val readAloudSettings = combine(
        readAloudSettingsGateway.settings,
        readSettingsGateway.settings,
    ) { aloud, read ->
        toReadAloudSettingsUiState(aloud, read)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = toReadAloudSettingsUiState(
            aloud = readAloudSettingsGateway.currentSettings,
            read = readSettingsGateway.currentSettings,
        ),
    )

    val uiState = combine(
        coordinator.state,
        AppConfigStore.observeInt(PreferKey.readAloudPlayerBgMode),
        activeSheet,
        // 排版变更（字号/行距等）唯一通知：revision 递增即重建快照，
        // 正文行字号跟随阅读页当前排版样式（与 ReadBookViewModel.collectReadStyle 同源约定）。
        readStyleGateway.state,
    ) { source, bgMode, sheet, _ ->
        toUiState(source, bgMode ?: ReadAloudBgMode.Blur, sheet)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = toUiState(coordinator.snapshot(), readBgMode(), null),
    )

    private val _effects = MutableSharedFlow<ReadAloudPlayerEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    fun onIntent(intent: ReadAloudPlayerIntent) {
        when (intent) {
            ReadAloudPlayerIntent.Refresh -> coordinator.refresh()
            ReadAloudPlayerIntent.TogglePause -> coordinator.togglePause()
            ReadAloudPlayerIntent.StopReadAloud -> coordinator.stop()
            ReadAloudPlayerIntent.PreviousParagraph -> coordinator.previousParagraph()
            ReadAloudPlayerIntent.NextParagraph -> coordinator.nextParagraph()
            ReadAloudPlayerIntent.PreviousChapter -> coordinator.previousChapter()
            ReadAloudPlayerIntent.NextChapter -> coordinator.nextChapter()
            ReadAloudPlayerIntent.SwitchToClassic ->
                effect(ReadAloudPlayerEffect.ReturnToClassic(uiState.value.bookUrl))
            ReadAloudPlayerIntent.CycleBgMode -> cycleBgMode()
            is ReadAloudPlayerIntent.SelectChapter -> coordinator.selectChapter(intent.index)
            is ReadAloudPlayerIntent.SetBgMode -> AppConfigStore.putInt(
                PreferKey.readAloudPlayerBgMode,
                intent.value,
            )
            is ReadAloudPlayerIntent.SetSpeed -> viewModelScope.launch {
                coordinator.setSpeed(intent.value)
            }
            is ReadAloudPlayerIntent.SetTimer -> viewModelScope.launch {
                coordinator.setTimer(intent.minutes)
            }
            is ReadAloudPlayerIntent.SetTimerMode -> viewModelScope.launch {
                coordinator.setTimerMode(ReadAloudTimerMode.fromStorage(intent.value))
            }

            is ReadAloudPlayerIntent.SetTimerChapters -> viewModelScope.launch {
                coordinator.setTimerChapters(intent.value)
            }
            is ReadAloudPlayerIntent.SetFinishCurrentChapterAfterTimer ->
                viewModelScope.launch {
                    coordinator.setFinishCurrentChapterAfterTimer(intent.value)
                }
            is ReadAloudPlayerIntent.OpenSheet -> activeSheet.value = intent.sheet
            ReadAloudPlayerIntent.DismissSheet -> activeSheet.value = null
            is ReadAloudPlayerIntent.SeekTo -> coordinator.seekTo(
                chapterPosition = intent.chapterPosition,
                chapterLength = uiState.value.chapterLength,
            )
        }
    }

    /** 听书页配置卡片的设置写入；与阅读界面共用同一份设置语义。 */
    fun onConfigIntent(
        option: ReadAloudConfigOption,
        value: String = "",
        selected: Boolean = false,
        intValue: Int = 0,
    ) {
        viewModelScope.launch {
            when (option) {
                ReadAloudConfigOption.DefaultInterface -> readAloudSettingsGateway.update {
                    it.copy(
                        defaultInterface = value.takeIf { candidate ->
                            candidate in ReadAloudSettingsRepository.AVAILABLE_INTERFACES
                        } ?: ReadAloudSettingsRepository.DEFAULT_INTERFACE_CLASSIC
                    )
                }

                ReadAloudConfigOption.ShowCapsule ->
                    readAloudSettingsGateway.update { it.copy(showReadAloudCapsule = selected) }

                ReadAloudConfigOption.CapsuleAutoCollapse ->
                    readAloudSettingsGateway.update { it.copy(capsuleAutoCollapse = selected) }

                ReadAloudConfigOption.IgnoreAudioFocus ->
                    readAloudSettingsGateway.update { it.copy(ignoreAudioFocus = selected) }

                ReadAloudConfigOption.PauseOnPhoneCall -> readAloudSettingsGateway.update {
                    it.copy(pauseReadAloudWhilePhoneCalls = selected)
                }

                ReadAloudConfigOption.WakeLock ->
                    readAloudSettingsGateway.update { it.copy(readAloudWakeLock = selected) }

                ReadAloudConfigOption.KeepOnExit ->
                    readAloudSettingsGateway.update { it.copy(keepReadAloudOnExit = selected) }

                ReadAloudConfigOption.MediaButtonPerNext ->
                    readAloudSettingsGateway.update { it.copy(mediaButtonPerNext = selected) }

                ReadAloudConfigOption.AndroidMediaControl -> readAloudSettingsGateway.update {
                    it.copy(androidMediaControlEnabled = selected)
                }

                ReadAloudConfigOption.SystemMediaCompat -> readAloudSettingsGateway.update {
                    it.copy(systemMediaControlCompatibilityChange = selected)
                }

                ReadAloudConfigOption.StreamAudio -> {
                    readAloudSettingsGateway.update { it.copy(streamReadAloudAudio = selected) }
                    // 流式输出与耳机媒体键播报互斥，打开流式即关掉媒体键转发
                    if (selected) postEvent(EventBus.MEDIA_BUTTON, false)
                }

                ReadAloudConfigOption.SpeechAnalysisMode -> {
                    // 非规则模式要求已配置 AI 模型，否则拒绝切换并提示
                    if (value != "rule") {
                        val configured = aiProfileGateway.getTaskPreset(AiTaskType.ANALYZE_SPEECH)
                            ?: aiProfileGateway.getTaskPreset(AiTaskType.CHAT)
                        if (configured == null) {
                            effect(ReadAloudPlayerEffect.SpeechAnalysisAiModelRequired)
                            return@launch
                        }
                    }
                    readAloudSettingsGateway.update { it.copy(speechAnalysisMode = value) }
                }

                ReadAloudConfigOption.SpeechAnalysisReasoningLevel -> {
                    // 关闭思考模式是 AI 朗读分析的默认值：默认思考的模型（智谱 GLM 等）
                    // 只把内容放在 reasoning_content 里，分析会直接失败。
                    val level = AiReasoningLevel.fromStorage(value, AiReasoningLevel.OFF)
                    readAloudSettingsGateway.update {
                        it.copy(speechAnalysisReasoningLevel = level.storageValue)
                    }
                }


                ReadAloudConfigOption.UseMultiSpeaker -> {
                    // 正在朗读时必须重启朗读服务才能换掉合成管线；
                    // 重启前记住页内位置，等服务真的回到 Idle 再重放，避免新旧管线叠音。
                    readAloudSettingsGateway.update { it.copy(useMultiSpeaker = selected) }
                    restartReadAloudPipeline(readAloudSessionStore, application)
                }

                ReadAloudConfigOption.ContentSplit -> {
                    val (mode, symbols) = ReadAloudContentSplitSetting.decode(value)
                    readAloudSettingsGateway.update {
                        it.copy(
                            contentSplitMode = mode.storageValue,
                            contentSplitSymbols = ReadAloudSplitSymbol.storageValues(symbols),
                        )
                    }
                }

                ReadAloudConfigOption.PreDownloadNum ->
                    readSettingsGateway.update { it.copy(preDownloadNum = intValue) }

                ReadAloudConfigOption.PreSynthesisConcurrency -> readAloudSettingsGateway.update {
                    it.copy(ttsPreSynthesisConcurrency = intValue.coerceIn(1, 8))
                }

                ReadAloudConfigOption.ParagraphInterval -> readAloudSettingsGateway.update {
                    it.copy(ttsParagraphInterval = intValue)
                }

                ReadAloudConfigOption.AudioCacheCleanTime -> readAloudSettingsGateway.update {
                    it.copy(audioCacheCleanTime = intValue)
                }

                ReadAloudConfigOption.ResetCapsulePosition -> readAloudSettingsGateway.update {
                    it.copy(capsuleOffsetX = 0f, capsuleOffsetY = 0f)
                }
            }
        }
    }

    /** 清除朗读音频缓存；语义与阅读器宿主 ReadAloudDelegate.clearTtsCache 一致。 */
    fun clearTtsCache() {
        TTSCacheUtils.clearTtsCache()
        effect(ReadAloudPlayerEffect.TtsCacheCleared)
    }

    private fun cycleBgMode() {
        val next = when (readBgMode()) {
            ReadAloudBgMode.Solid -> ReadAloudBgMode.Blur
            ReadAloudBgMode.Blur -> ReadAloudBgMode.FlowingLight
            ReadAloudBgMode.FlowingLight -> ReadAloudBgMode.Transparent
            else -> ReadAloudBgMode.Solid
        }
        AppConfigStore.putInt(PreferKey.readAloudPlayerBgMode, next)
    }

    private fun toUiState(
        source: ReadAloudPlayerSourceState,
        bgMode: Int,
        sheet: ReadAloudPlayerSheet?,
    ): ReadAloudPlayerUiState {
        val activeIndex = source.textLines.indexOfLast {
            it.chapterPosition <= source.chapterPosition
        }
        val chapters = source.chapters.map { chapter ->
            PlayerChapterUi(
                index = chapter.index,
                title = chapter.title,
                isVolume = chapter.isVolume,
                tocLevel = chapter.tocLevel,
            )
        }.toImmutableList()
        return ReadAloudPlayerUiState(
            bookUrl = source.bookUrl,
            bookName = source.bookName,
            author = source.author,
            coverPath = source.coverPath,
            sourceOrigin = source.sourceOrigin,
            chapterIndex = source.chapterIndex,
            chapterTitle = source.chapterTitle,
            chapters = chapters,
            chapterText = source.chapterText,
            textLines = source.textLines,
            activeTextLine = activeIndex,
            currentText = source.textLines.getOrNull(activeIndex)?.text ?: source.playbackText,
            nextText = source.textLines.getOrNull(activeIndex + 1)?.text.orEmpty(),
            chapterPosition = source.chapterPosition,
            chapterLength = source.chapterLength,
            engineName = source.engineName,
            speakerName = source.speakerName,
            isPaused = source.isPaused,
            isPreparing = source.isPreparing,
            readAloudRunning = BaseReadAloudService.isRun,
            speed = source.speed,
            timerMinutes = source.timerMinutes,
            timerMode = source.timerMode,
            timerChapters = source.timerChapters,
            finishCurrentChapterAfterTimer = source.finishCurrentChapterAfterTimer,
            bgMode = bgMode,
            activeSheet = sheet,
            bodyTextSize = ReadBookConfig.textSize,
        )
    }

    // internal：配置卡片意图映射（ReadAloudConfigIntentMapper）在类外发跳页 Effect。
    internal fun effect(value: ReadAloudPlayerEffect) {
        _effects.tryEmit(value)
    }

    private fun readBgMode(): Int {
        return AppConfigStore.preferences.compatDsInt(PreferKey.readAloudPlayerBgMode)
            ?: ReadAloudBgMode.Blur
    }

}

private fun toReadAloudSettingsUiState(
    aloud: ReadAloudSettings,
    read: ReadSettings,
): ReadAloudSettingsUiState = ReadAloudSettingsUiState(
    defaultReadAloudInterface = aloud.defaultInterface,
    showReadAloudCapsule = aloud.showReadAloudCapsule,
    capsuleAutoCollapse = aloud.capsuleAutoCollapse,
    readAloudIgnoreAudioFocus = aloud.ignoreAudioFocus,
    readAloudPauseOnPhoneCall = aloud.pauseReadAloudWhilePhoneCalls,
    readAloudWakeLock = aloud.readAloudWakeLock,
    readAloudKeepOnExit = aloud.keepReadAloudOnExit,
    readAloudMediaButtonPerNext = aloud.mediaButtonPerNext,
    readAloudAndroidMediaControl = aloud.androidMediaControlEnabled,
    readAloudSystemMediaCompat = aloud.systemMediaControlCompatibilityChange,
    readAloudStreamAudio = aloud.streamReadAloudAudio,
    speechAnalysisMode = aloud.speechAnalysisMode,
    speechAnalysisReasoningLevel = aloud.speechAnalysisReasoningLevel,
    useMultiSpeaker = aloud.useMultiSpeaker,
    readAloudContentSplitMode = aloud.contentSplitMode,
    readAloudContentSplitSymbols = aloud.contentSplitSymbols,
    preDownloadNum = read.preDownloadNum,
    preSynthesisConcurrency = aloud.ttsPreSynthesisConcurrency,
    readAloudParagraphInterval = aloud.ttsParagraphInterval,
    audioCacheCleanTime = aloud.audioCacheCleanTime,
)
