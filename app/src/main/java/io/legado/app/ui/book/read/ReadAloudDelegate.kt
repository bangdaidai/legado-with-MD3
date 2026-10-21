package io.legado.app.ui.book.read

import android.content.Context
import android.speech.tts.TextToSpeech
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.repository.HttpTtsRepository
import io.legado.app.data.repository.ReadAloudSettingsRepository
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.model.AiReasoningLevel
import io.legado.app.domain.model.AiTaskType
import io.legado.app.domain.model.PlaybackTimer
import io.legado.app.domain.model.readaloud.ReadAloudSessionStatus
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
import io.legado.app.ui.book.readaloud.ReadAloudPlayerOverlayBus
import io.legado.app.ui.book.readaloud.player.applyReaderRenderedSwitch
import io.legado.app.utils.TTSCacheUtils
import io.legado.app.utils.postEvent
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 朗读域。
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
    private val aiProfileGateway: AiProfileGateway,
) {

    val readerSessions = combine(
        readAloudSessionStore.state, ReadBook.snapshot,
        ReadAloud.bookSession.state.map { it.book?.bookUrl }.distinctUntilChanged(),
    ) { session, reader, playingBookUrl ->
        session to (reader.bookUrl != null && reader.bookUrl == playingBookUrl)
    }

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
                        speechAttributionStrictness = prefs.speechAttributionStrictness,
                        useMultiSpeaker = prefs.useMultiSpeaker,
                        multiRoleCast = prefs.multiRoleCast,
                        // 漏填这一项会出现「开关显示关、正文胶囊却还是开」：
                        // 胶囊读的是 ReadConfig.bgmAssign（持久值），开关读的是这里的状态
                        bgmAssign = prefs.bgmAssign,
                        // 总音量存在配乐自己的 prefs 里（与配乐库/段内音量同一处），不在朗读设置模型中
                        bgmVolume = io.legado.app.help.readaloud.cast.BgmPoolStore.volume(),
                        defaultReadAloudInterface = prefs.defaultInterface,
                        preDownloadNum = host.preDownloadNum,
                        // 音频预取章数住在朗读设置这一份，与上面的正文预下载是两个键，别混
                        audioPreDownloadNum = prefs.audioPreDownloadNum,
                        speechAheadAnalysisChapters = prefs.speechAheadAnalysisChapters,
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
        if (ReadBook.isCurrentBookReadAloud && BaseReadAloudService.isPlay() && chapterStart > 0) {
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
    fun prevChapter() = ReadAloud.previousChapter(context)

    fun nextChapter() = ReadAloud.nextChapter(context)

    /** 回到朗读位置：恢复页面跟随朗读，并跳到朗读所在章节/字符位置。全程不打断当前朗读。 */
    fun backToSpeakingPosition() {
        if (!ReadBook.isCurrentBookReadAloud) return
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

    /**
     * 离开听书页回到阅读页的那一刻：跟随状态下立即「页面追声音」，不等排版批次落地的兜底。
     * 用户手动翻页脱离后页面位置归用户，不回拉；backToSpeakingPosition 内部对
     * 跨章/同章、加载未完成各有承接路径。
     */
    fun syncReaderToVoiceOnReturn() {
        val state = host.uiState
        if (!state.isReadAloudRunning || !state.readAloudFollow) return
        backToSpeakingPosition()
    }

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
     * 打开听书播放弹层。
     *
     * 播放弹层是 Activity 级全局浮层（[ReadAloudPlayerOverlayBus]），不占导航栈，
     * 所以这里只请求宿主把它拉起来；先把菜单与已有弹层收掉，
     * 关闭播放弹层时不会停在半开的菜单上。
     */
    fun openPlayer() {
        host.updateState { it.copy(menuState = ReadBookMenuState(), activeSheet = null) }
        ReadAloudPlayerOverlayBus.request()
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

    /** 经典朗读控制面板：阅读菜单里的一页，不遮挡正文区域之外的交互。 */
    fun openClassicControls() {
        host.updateState { it.copy(activeSheet = null) }
        host.openReadMenuRoute(ReadBookMenuRoute.ReadAloud)
    }

    /** 卡片里滑页/点 tab：tab 住在状态里，弹层被拆掉重建才不会退回常规。 */
    fun setConfigTab(tab: Int) {
        // 卡片只有常规 / 引擎与音色两页，角色与配乐早已是整屏页，不再占 tab。
        host.updateState { it.copy(readAloudConfigTab = tab.coerceIn(0, 1)) }
    }

    /*
     * 下面三个目的地是整屏 NavKey。`activeSheet` 与 `readAloudConfigTab` 分开管：
     * 推送整屏页时不清空 `activeSheet`，返回时弹层自己摊回来、tab 由
     * `readAloudConfigTab` 记住；朗读设置卡片被整屏页盖住时由 ReadBookScreen
     * 按导航栈顶收起，避免窗口级浮层悬在新页面上面。
     */
    fun openTtsEnginesAndVoices() {
        host.emitEffect(ReadBookEffect.OpenTtsEnginesAndVoices)
    }

    fun openTtsCache() {
        host.emitEffect(ReadBookEffect.OpenTtsCache)
    }

    fun openBookVoiceCasting() {
        ReadBook.book?.bookUrl?.let { bookUrl ->
            host.emitEffect(ReadBookEffect.OpenBookVoiceCasting(bookUrl))
        }
    }

    /** 朗读设置里的整屏子页（角色声音池 / 背景音乐池 / 朗读胶囊外观）：只负责把意图发出去。 */
    fun openReadAloudSubPage(page: ReadAloudSubPage) {
        host.emitEffect(ReadBookEffect.OpenReadAloudSubPage(page))
    }

    fun openSystemTtsSettings() {
        host.emitEffect(ReadBookEffect.OpenSystemTtsSettings)
    }

    fun clearTtsCache() {
        TTSCacheUtils.clearTtsCache()
        host.emitEffect(
            ReadBookEffect.TtsCacheCleared(context.getString(R.string.clear_cache_success))
        )
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

    fun setDefaultInterface(value: String) {
        updateSettings {
            it.copy(
                defaultInterface = value.takeIf { candidate ->
                    candidate in ReadAloudSettingsRepository.AVAILABLE_INTERFACES
                } ?: ReadAloudSettingsRepository.DEFAULT_INTERFACE_CLASSIC
            )
        }
        host.updateState { it.copy(defaultReadAloudInterface = value) }
    }

    /** 非规则模式要求已配置 AI 模型，否则拒绝切换并提示。 */
    fun setSpeechAnalysisMode(value: String) {
        scope.launch {
            if (value != "rule") {
                val configured = aiProfileGateway.getTaskPreset(AiTaskType.ANALYZE_SPEECH)
                    ?: aiProfileGateway.getTaskPreset(AiTaskType.CHAT)
                if (configured == null) {
                    host.emitEffectAwait(
                        ReadBookEffect.ShowToast(
                            context.getString(R.string.speech_analysis_ai_model_required)
                        )
                    )
                    return@launch
                }
            }
            readAloudSettingsRepository.update { it.copy(speechAnalysisMode = value) }
            host.updateState { it.copy(speechAnalysisMode = value) }
        }
    }

    /**
     * 朗读分析的推理级别。关闭思考模式是 AI 朗读分析的默认值：默认思考的模型（智谱 GLM 等）
     * 只把内容放在 reasoning_content 里，分析会直接失败。
     */
    fun setSpeechAnalysisReasoningLevel(value: String) {
        val level = AiReasoningLevel.fromStorage(value, AiReasoningLevel.OFF)
        updateSettings { it.copy(speechAnalysisReasoningLevel = level.storageValue) }
        host.updateState { it.copy(speechAnalysisReasoningLevel = level.storageValue) }
    }

    /**
     * 多角色朗读开关（底栏「多角色朗读」）。
     *
     * 它同时决定三件事：有没有音色计划（旁白/角色音全在那份计划里）、正文按哪种粒度切分、
     * 正文里的角色标记要不要注入，所以正在朗读时必须换掉朗读服务——不重启就是开关只改了
     * 胶囊：开着不换音色，关着又停在配音页选的旁白音上回不去默认引擎。
     * 重排当前章让胶囊即时出现/消失，重启前记住页内位置，等旧服务真的 Idle 再重放，避免叠音。
     */
    fun setMultiRoleCast(value: Boolean) {
        scope.launch {
            readAloudSettingsRepository.update { it.copy(multiRoleCast = value) }
            host.updateState { it.copy(multiRoleCast = value) }
            applyReaderRenderedSwitch(readAloudSessionStore, context, readsDurChapterAloud())
        }
    }

    /**
     * 背景音乐分配开关：正文段首的 ♪ 胶囊与朗读时的配乐轨都由它门控，
     * 同样要重载正文让胶囊即时出现/消失。
     */
    fun setBgmAssign(value: Boolean) {
        host.updateState { it.copy(bgmAssign = value) }
        scope.launch {
            readAloudSettingsRepository.update { it.copy(bgmAssign = value) }
            applyReaderRenderedSwitch(readAloudSessionStore, context, readsDurChapterAloud())
        }
    }

    /**
     * 朗读服务正在读的就是这一章 —— 重排时把朗读位置交给 `ReadAloud.syncLayout()` 同步，
     * 而不是让它按当前页重新起播（不保位的重载会把读过半章的朗读拽回章首）。
     */
    private fun readsDurChapterAloud(): Boolean =
        ReadBook.isCurrentBookReadAloud &&
            BaseReadAloudService.currentChapterIndex == ReadBook.durChapterIndex

    /**
     * 背景音乐总音量：只写 prefs + 刷状态，不重排当前章（音量不在胶囊文字里）。
     *
     * 朗读中的配乐轨靠 BgmPoolStore.volumeVersion 发现改动，拖滑杆时即时跟着变。
     */
    fun setBgmVolume(value: Float) {
        val volume = value.coerceIn(0f, 1f)
        host.updateState { it.copy(bgmVolume = volume) }
        io.legado.app.help.readaloud.cast.BgmPoolStore.setVolume(volume)
    }

    /**
     * 原版「多说话人」开关。与上面的多角色朗读一样要换掉合成管线：
     * 正在朗读时重启服务，重启前记住页内位置，等服务真的回到 Idle 再重放，避免新旧管线叠音。
     */
    fun setUseMultiSpeaker(value: Boolean) {
        scope.launch {
            val shouldRestart = ReadBook.isCurrentBookReadAloud
            val resumePlaying = shouldRestart && !BaseReadAloudService.pause
            val chapterPosition = readAloudSessionStore.state.value.playback.chapterPosition
            readAloudSettingsRepository.update { it.copy(useMultiSpeaker = value) }
            host.updateState { it.copy(useMultiSpeaker = value) }
            if (shouldRestart) {
                restartReadAloudWithNewPipeline(resumePlaying, chapterPosition)
            }
        }
    }

    /**
     * 换掉朗读管线后重启朗读服务。
     *
     * 先 stop 并等旧服务真的回到 Idle 再重放，否则新旧两条管线会同时出声（叠音）。
     * [chapterPosition] 是重启前记住的页内位置，让用户听回来刚才那句而不是章首。
     */
    private suspend fun restartReadAloudWithNewPipeline(resumePlaying: Boolean, chapterPosition: Int) {
        if (ReadBook.readerChapterInputWindow.current == null) return
        ReadAloud.stop(context)
        val stopped = withTimeoutOrNull(2_000) {
            readAloudSessionStore.state.first {
                it.status == ReadAloudSessionStatus.Idle
            }
        }
        if (stopped == null) return
        ReadAloud.refreshReadAloudClass()
        ReadAloud.play(
            context = context,
            play = resumePlaying,
            chapterPosition = chapterPosition.coerceAtLeast(0),
        )
    }

    private inline fun updateSettings(
        crossinline transform: (ReadAloudSettings) -> ReadAloudSettings,
    ) {
        scope.launch {
            readAloudSettingsRepository.update { transform(it) }
        }
    }
}
