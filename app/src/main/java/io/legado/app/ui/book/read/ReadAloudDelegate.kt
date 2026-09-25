package io.legado.app.ui.book.read

import android.content.Context
import android.speech.tts.TextToSpeech
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.repository.HttpTtsRepository
import io.legado.app.data.repository.ReadAloudSettingsRepository
import io.legado.app.domain.model.PlaybackTimer
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.VoiceCatalogEntry
import io.legado.app.domain.model.settings.ReadAloudSettings
import io.legado.app.domain.model.settings.ReadAloudTimerMode
import io.legado.app.domain.usecase.SyncReadAloudVoicesUseCase
import io.legado.app.help.readaloud.HttpTtsVoiceCatalog
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudSessionStore
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.utils.postEvent
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 朗读域（R2.2 续批）。
 *
 * 管经典朗读面板一侧的朗读设置读写、播放传输控制和声音目录同步。
 * 朗读设置整页（引擎/数值/缓存清理等）由全局的 ReadAloudPlayerViewModel 承载，不在这里。
 *
 * **无自持状态**：朗读的 20 来个字段散落在 [ReadBookUiState] 里，被 `ReadAloudScreen`、
 * `ReadBookScreen`、`ReadBookRouteScreen` 多处直读——
 * 搬出去要同时改这些 composable 的入参。故与 [ReadConfigUpdateDelegate] /
 * [ReadButtonConfigDelegate] 同形：状态留在 UiState，读写一律经 [Host]。
 */
class ReadAloudDelegate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
    private val readAloudSettingsRepository: ReadAloudSettingsRepository,
    private val readAloudSessionStore: ReadAloudSessionStore,
    private val httpTtsRepository: HttpTtsRepository,
    private val syncReadAloudVoicesUseCase: SyncReadAloudVoicesUseCase,
) {

    interface Host {
        val uiState: ReadBookUiState

        /** 朗读预下载章节数住在 ReadPreferences，不在 ReadBookUiState。 */
        val preDownloadNum: Int

        /** 系统 TTS 引擎清单（VM 侧 lazy，构造代价高，只取一次）。 */
        val systemTtsEngines: List<TextToSpeech.EngineInfo>

        fun updateState(transform: (ReadBookUiState) -> ReadBookUiState)

        fun emitEffect(effect: ReadBookEffect)

        suspend fun emitEffectAwait(effect: ReadBookEffect)

        fun openReadMenuRoute(route: ReadBookMenuRoute)

        /** 朗读进度（TTS 回调上报的章内偏移），VM 用独立 flow 暴露给胶囊。 */
        fun publishReadAloudProgress(chapterStart: Int)
    }

    /** 订阅朗读设置，投影进 UiState。VM 构造时调一次。 */
    fun collectPreferences() {
        scope.launch {
            readAloudSettingsRepository.preferences.collect { prefs ->
                host.updateState {
                    it.copy(
                        readAloudIgnoreAudioFocus = prefs.ignoreAudioFocus,
                        readAloudPauseOnPhoneCall = prefs.pauseReadAloudWhilePhoneCalls,
                        readAloudWakeLock = prefs.readAloudWakeLock,
                        readAloudKeepOnExit = prefs.keepReadAloudOnExit,
                        showReadAloudCapsule = prefs.showReadAloudCapsule,
                        capsuleAutoCollapse = prefs.capsuleAutoCollapse,
                        readAloudCapsuleOffsetX = prefs.capsuleOffsetX,
                        readAloudCapsuleOffsetY = prefs.capsuleOffsetY,
                        readAloudMediaButtonPerNext = prefs.mediaButtonPerNext,
                        readAloudByPage = prefs.readAloudByPage,
                        readAloudContentSplitMode = prefs.contentSplitMode,
                        readAloudContentSplitSymbols = prefs.contentSplitSymbols.toImmutableSet(),
                        readAloudSystemMediaCompat =
                            prefs.systemMediaControlCompatibilityChange,
                        readAloudAndroidMediaControl = prefs.androidMediaControlEnabled,
                        readAloudStreamAudio = prefs.streamReadAloudAudio,
                        readAloudTtsFollowSys = prefs.ttsFollowSys,
                        readAloudTtsSpeechRate = prefs.ttsSpeechRate,
                        readAloudTtsTimer = prefs.ttsTimer,
                        readAloudFinishCurrentChapterAfterTimer =
                            prefs.finishCurrentChapterAfterTimer,
                        readAloudTimerMode = prefs.timerMode,
                        readAloudTimerChapters = prefs.timerChapters,
                        speechAnalysisMode = prefs.speechAnalysisMode,
                        speechAnalysisReasoningLevel = prefs.speechAnalysisReasoningLevel,
                        useMultiSpeaker = prefs.useMultiSpeaker,
                        defaultReadAloudInterface = prefs.defaultInterface,
                        preDownloadNum = host.preDownloadNum,
                        audioCacheCleanTime = prefs.audioCacheCleanTime,
                        readAloudParagraphInterval = prefs.ttsParagraphInterval,
                    )
                }
            }
        }
    }

    /**
     * 刷新声音目录。朗读引擎可能在 cloudtts 页被新增/删除，
     * 打开朗读设置整页和 VM 构造时各同步一次。
     */
    suspend fun syncConfiguredTtsVoices(
        systemTtsLabel: String = context.getString(R.string.system_tts),
        httpTtsList: List<HttpTTS> = httpTtsRepository.getAllSync(),
    ) {
        // jsLib 里定义了 voices() 的引擎，一条引擎展开成多个音色；脚本执行放到 IO
        val httpEntries = withContext(IO) {
            HttpTtsVoiceCatalog.catalogEntries(httpTtsList)
        }
        syncReadAloudVoicesUseCase(
            entries = buildList {
                add(
                    VoiceCatalogEntry(
                        engineType = ReadAloudVoice.ENGINE_SYSTEM,
                        engineId = "",
                        displayName = systemTtsLabel,
                    )
                )
                host.systemTtsEngines.forEach { engine ->
                    add(
                        VoiceCatalogEntry(
                            engineType = ReadAloudVoice.ENGINE_SYSTEM,
                            engineId = engine.name,
                            displayName = engine.label,
                        )
                    )
                }
                addAll(httpEntries)
            },
            managedSources = ReadAloudVoice.CATALOG_MANAGED,
            removeMissingEngineTypes = setOf(ReadAloudVoice.ENGINE_HTTP),
        )
    }

    // --- 播放控制 ---

    fun updateProgress(chapterStart: Int) {
        if (BaseReadAloudService.isPlay() && chapterStart > 0) {
            host.publishReadAloudProgress(chapterStart)
        }
    }

    fun stop() {
        ReadAloud.stop(context)
        host.updateState {
            it.copy(
                isReadAloudRunning = false,
                isReadAloudPaused = false,
                isReadAloudPreparing = false,
            )
        }
    }

    fun prevParagraph() = ReadAloud.prevParagraph(context)

    fun nextParagraph() = ReadAloud.nextParagraph(context)

    /** 朗读面板换章：朗读驱动的章节移动，页面跟随朗读，不视为手动脱离。 */
    fun prevChapter() = BaseReadAloudService.withSpeechNavigation {
        ReadBook.moveToPrevChapter(upContent = true, toLast = false)
    }

    fun nextChapter() = BaseReadAloudService.withSpeechNavigation {
        ReadBook.moveToNextChapter(true)
    }

    /** 回到朗读位置：恢复页面跟随朗读，并跳到朗读所在章节/字符位置。全程不打断当前朗读。 */
    fun backToSpeakingPosition() {
        readAloudSessionStore.restoreReadAloudFollow()
        val speakingChapterIndex = BaseReadAloudService.currentChapterIndex
        val speakingChapterStart = BaseReadAloudService.currentProgress
        if (speakingChapterIndex < 0) return
        // currentProgress 为正在朗读的精确章内位置（onRangeStart 段内偏移上报），整段高亮落在当前段
        val chapterStart = speakingChapterStart.coerceAtLeast(0)
        if (speakingChapterIndex != ReadBook.durChapterIndex) {
            // 跳到朗读位置属于朗读相关的页面移动，不能触发手动脱离
            BaseReadAloudService.withSpeechNavigation {
                ReadBook.openChapter(speakingChapterIndex, chapterStart) {
                    ReadBook.upTextChapterAloudSpan(chapterStart)
                }
            }
        } else {
            ReadBook.syncReadAloudPage(speakingChapterIndex, chapterStart)
            ReadBook.upTextChapterAloudSpan(chapterStart)
        }
    }

    // --- 界面入口 ---

    /** 媒体键/胶囊触发的默认朗读界面：按设置决定开播放器还是经典控制面板。 */
    fun openDefaultInterface() {
        syncVoiceToVisiblePage()
        if (
            host.uiState.defaultReadAloudInterface ==
            ReadAloudSettingsRepository.DEFAULT_INTERFACE_PLAYER
        ) {
            openPlayer()
        } else {
            host.openReadMenuRoute(ReadBookMenuRoute.ReadAloud)
        }
    }

    /**
     * 「从哪个阅读页进的听书，就从哪一页读」：朗读服务已活着、但阅读页已被手动翻离
     * 声音位置（脱离跟随）时，点听书先把声音按当前可见页重新起读，再开界面——
     * 否则听书页显示的还是声音那本旧章，返回时阅读页又会被回拉，两处都"对不上"。
     * 跟随状态下声音就在这一页里，不动声音，保持"仅打开界面"的现有语义。
     */
    private fun syncVoiceToVisiblePage() {
        val state = host.uiState
        if (!state.isReadAloudRunning || state.readAloudFollow) return
        // 默认参数即"从当前页页首起读"，且新轮起轮会恢复跟随
        ReadBook.readAloud()
    }

    /**
     * 打开听书播放界面。
     *
     * 播放界面是 Navigation 3 目的地而非阅读器弹层，所以这里发导航意图；
     * 先把菜单状态收起来，返回阅读界面时不会停在半开的菜单上。
     */
    fun openPlayer() {
        host.updateState { it.copy(menuState = ReadBookMenuState(), activeSheet = null) }
        host.emitEffect(ReadBookEffect.OpenReadAloudPlayer)
    }

    /**
     * 打开朗读设置整页（Navigation 3 目的地）。
     *
     * 原来是阅读器内的底部弹层；弹层是 dialog 窗口，跳独立整页时层级会来回塌陷，
     * 改成页面后栈语义天然正确。菜单叠层（经典朗读面板）与旧弹层在同一帧收起，
     * 返回阅读页时停在干净的页面上。
     */
    fun openConfigPage() {
        host.updateState { it.copy(menuState = ReadBookMenuState(), activeSheet = null) }
        scope.launch { syncConfiguredTtsVoices() }
        host.emitEffect(ReadBookEffect.OpenReadAloudConfig(ReadBook.book?.bookUrl))
    }

    // --- 开关类设置 ---

    /** 按页朗读（经典面板）；开启时不再响应耳机媒体键逐句播报。 */
    fun setByPage(value: Boolean) {
        updateSettings { it.copy(readAloudByPage = value) }
        if (value) postEvent(EventBus.MEDIA_BUTTON, false)
    }

    fun setCapsulePosition(x: Float, y: Float) {
        host.updateState { it.copy(readAloudCapsuleOffsetX = x, readAloudCapsuleOffsetY = y) }
        updateSettings { it.copy(capsuleOffsetX = x, capsuleOffsetY = y) }
    }

    fun setTtsFollowSys(value: Boolean) {
        updateSettings { it.copy(ttsFollowSys = value) }
        host.updateState { it.copy(readAloudTtsFollowSys = value) }
    }

    fun setTtsTimer(value: Int) {
        val timer = PlaybackTimer.normalize(value)
        ReadAloud.setTimer(context, timer)
        // 两种定时互斥：设分钟定时即切到分钟模式并清掉章节配额
        updateSettings {
            it.copy(
                ttsTimer = timer,
                timerMode = ReadAloudTimerMode.Minute.storageValue,
                timerChapters = 0,
            )
        }
        ReadAloud.setTimerChapters(context, 0)
        host.updateState {
            it.copy(
                readAloudTtsTimer = timer,
                readAloudTimerMode = ReadAloudTimerMode.Minute.storageValue,
                readAloudTimerChapters = 0,
            )
        }
    }

    fun setFinishCurrentChapterAfterTimer(value: Boolean) {
        updateSettings { it.copy(finishCurrentChapterAfterTimer = value) }
        host.updateState { it.copy(readAloudFinishCurrentChapterAfterTimer = value) }
    }

    fun setTimerMode(mode: ReadAloudTimerMode) {
        val prefs = readAloudSettingsRepository.currentSettings
        val minutes = if (mode == ReadAloudTimerMode.Minute) prefs.ttsTimer else 0
        val chapters = if (mode == ReadAloudTimerMode.Chapter) prefs.timerChapters else 0
        updateSettings {
            it.copy(
                timerMode = mode.storageValue,
                ttsTimer = minutes,
                timerChapters = chapters,
            )
        }
        ReadAloud.setTimer(context, minutes)
        ReadAloud.setTimerChapters(context, chapters)
        host.updateState {
            it.copy(
                readAloudTimerMode = mode.storageValue,
                readAloudTtsTimer = minutes,
                readAloudTimerChapters = chapters,
            )
        }
    }

    fun setTimerChapters(value: Int) {
        val chapters = PlaybackTimer.normalizeChapters(value)
        ReadAloud.setTimerChapters(context, chapters)
        updateSettings {
            it.copy(
                timerChapters = chapters,
                timerMode = ReadAloudTimerMode.Chapter.storageValue,
                ttsTimer = 0,
            )
        }
        // 切到章节模式要同时停掉正在跑的分钟倒计时
        ReadAloud.setTimer(context, 0)
        host.updateState {
            it.copy(
                readAloudTimerChapters = chapters,
                readAloudTimerMode = ReadAloudTimerMode.Chapter.storageValue,
                readAloudTtsTimer = 0,
            )
        }
    }

    fun setTtsSpeechRate(value: Int) {
        scope.launch {
            readAloudSettingsRepository.update { it.copy(ttsSpeechRate = value.coerceIn(0, 80)) }
            ReadAloud.upTtsSpeechRate(context)
        }
        host.updateState { it.copy(readAloudTtsSpeechRate = value) }
    }

    private inline fun updateSettings(
        crossinline transform: (ReadAloudSettings) -> ReadAloudSettings,
    ) {
        scope.launch {
            readAloudSettingsRepository.update { transform(it) }
        }
    }
}
