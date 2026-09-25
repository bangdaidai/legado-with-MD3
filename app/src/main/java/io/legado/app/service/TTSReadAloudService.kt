@file:Suppress("DEPRECATION")
package io.legado.app.service

import android.app.PendingIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.domain.gateway.ReadAloudSettingsGateway
import io.legado.app.domain.model.readaloud.ReadAloudPlaybackCursor
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import io.legado.app.domain.model.readaloud.SpeechEngineRoute
import io.legado.app.domain.model.readaloud.SpeechVoiceRouter
import io.legado.app.domain.model.readaloud.SystemTtsVoiceConfig
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.MediaHelp
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.ui.config.readConfig.ReadConfig
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 本地朗读
 */
class TTSReadAloudService : BaseReadAloudService(), KoinComponent {

    override val useSpeechPlaybackQueue: Boolean = true

    protected override val currentSpeechRate: Float
        get() = if (ReadConfig.ttsFollowSys) 1f else (speechRateSetting + 5) / 10f

    private val readAloudSettingsGateway: ReadAloudSettingsGateway by inject()
    @Volatile
    private var speechRateSetting: Int = 5

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitFinish = false
    private val ttsUtteranceListener = TTSUtteranceListener()
    private var speakJob: Coroutine<*>? = null
    private var utteranceStartPos = 0
    private var utteranceStartReadAloudNumber = 0
    private var needParagraphInterval = false // 是否需要进行段落间隔延迟
    private var activeEngine = ""
    private var activeVoiceName = ""
    private var defaultVoiceName = ""
    private var initGeneration = 0
    private var reportedUnplayableVoice = false
    private var reportedVoiceName: String? = null

    /** 播放会话号: 每次真正发声、停止、暂停、清理或整体换章时递增, 用于拒收旧会话的回调 */
    @Volatile
    private var speakSession = 0
    private val TAG = "TTSReadAloudService"

    override fun onCreate() {
        super.onCreate()
        speechRateSetting = readAloudSettingsGateway.currentSettings.ttsSpeechRate
        lifecycleScope.launch {
            readAloudSettingsGateway.settings.collect {
                speechRateSetting = it.ttsSpeechRate
            }
        }
        initTts()
    }

    override fun onDestroy() {
        super.onDestroy()
        clearTTS()
    }

    @Synchronized
    private fun initTts(engineOverride: String? = null) {
        ttsInitFinish = false
        val engine = engineOverride
            ?: GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine).getOrNull()?.value
        activeEngine = engine.orEmpty()
        val generation = ++initGeneration
        LogUtils.d(TAG, "initTts engine:$engine")
        textToSpeech = if (engine.isNullOrBlank()) {
            TextToSpeech(this) { status -> onTtsInitialized(status, generation) }
        } else {
            TextToSpeech(this, { status -> onTtsInitialized(status, generation) }, engine)
        }
        upSpeechRate()
    }

    @Synchronized
    fun clearTTS() {
        textToSpeech?.runCatching {
            stop()
            shutdown()
        }
        textToSpeech = null
        ttsInitFinish = false
        activeVoiceName = ""
        defaultVoiceName = ""
        reportedVoiceName = null
        initGeneration++
        speakSession++
    }

    private fun onTtsInitialized(status: Int, generation: Int) {
        if (generation != initGeneration) return
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.let {
                it.setOnUtteranceProgressListener(ttsUtteranceListener)
                defaultVoiceName = it.defaultVoice?.name.orEmpty()
                activeVoiceName = defaultVoiceName
                ttsInitFinish = true
                play()
            }
        } else {
            toastOnUi(R.string.tts_init_failed)
        }
    }

    @Synchronized
    override fun play() {
        if (hasSpeechPlaybackQueue) {
            val route = systemVoiceForCurrentCue()
            val requiredEngine = route.engineId
            if (requiredEngine != activeEngine || textToSpeech == null) {
                clearTTS()
                initTts(requiredEngine)
                return
            }
            applyVoice(route.speakerId)
            applyPreset(route)
        }
        if (!ttsInitFinish) return
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            diagVoice("朗读列表为空(引擎侧)", verbose = true)
            ReadBook.readAloud()
            return
        }
        super.play()
        MediaHelp.playSilentSound(this@TTSReadAloudService)
        
        // 捕获本次是否需要进行段落延迟，并将标志位复位（防多次触发）
        val isDelay = needParagraphInterval
        needParagraphInterval = false
        
        speakJob?.cancel()
        speakJob = execute {
            val interval = ReadConfig.ttsParagraphInterval.toLong()
            diagVoice("TTS起声 段$nowSpeak 延=$isDelay 隔${interval}ms", verbose = true)
            
            if (hasSpeechPlaybackQueue || interval > 0) {
                // 段落间隔模式：单段播放
                if (isDelay) {
                    delay(interval)
                }
                ensureActive()

                LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
                val session = ++speakSession
                val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
                var text = contentList[nowSpeak]
                if (paragraphStartPos > 0) {
                    text = text.substring(paragraphStartPos)
                }
                if (text.matches(AppPattern.notReadAloudRegex)) {
                    diagVoice("段${nowSpeak}全标点跳过", verbose = true)
                    ttsUtteranceListener.onDone(ttsUtteranceId(AppConst.APP_TAG, session, nowSpeak))
                    return@execute
                }
                // 出声文本原样带上（截断即可）：偏>0 时这里就是"每句只读两个字"的现场证据
                diagVoice("段${nowSpeak}出声[${text.length}字]$text", verbose = true)
                val result = tts.runCatching {
                    speak(text, TextToSpeech.QUEUE_FLUSH, null, ttsUtteranceId(AppConst.APP_TAG, session, nowSpeak))
                }.getOrElse {
                    AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                    TextToSpeech.ERROR
                }
                if (result == TextToSpeech.ERROR) {
                    AppLog.put("tts出错 尝试重新初始化")
                    clearTTS()
                    initTts()
                    return@execute
                }
                LogUtils.d(TAG, "朗读内容添加完成")
            } else {
                // 无间隔模式：保持原有的队列式连续播放，确保无缝衔接
                LogUtils.d(TAG, "朗读列表大小 ${contentList.size}")
                LogUtils.d(TAG, "朗读页数 ${readerReadAloudChapter?.pageCount}")
                val session = ++speakSession
                val tts = textToSpeech ?: throw NoStackTraceException("tts is null")
                val contentList = contentList
                var isAddedText = false
                for (i in nowSpeak until contentList.size) {
                    ensureActive()
                    var text = contentList[i]
                    if (paragraphStartPos > 0 && i == nowSpeak) {
                        text = text.substring(paragraphStartPos)
                    }
                    if (text.matches(AppPattern.notReadAloudRegex)) {
                        continue
                    }
                    if (!isAddedText) {
                        val result = tts.runCatching {
                            speak(text, TextToSpeech.QUEUE_FLUSH, null, ttsUtteranceId(AppConst.APP_TAG, session, i))
                        }.getOrElse {
                            AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                            TextToSpeech.ERROR
                        }
                        if (result == TextToSpeech.ERROR) {
                            AppLog.put("tts出错 尝试重新初始化")
                            clearTTS()
                            initTts()
                            return@execute
                        }
                    } else {
                        val result = tts.runCatching {
                            speak(text, TextToSpeech.QUEUE_ADD, null, ttsUtteranceId(AppConst.APP_TAG, session, i))
                        }.getOrElse {
                            AppLog.put("tts出错\n${it.localizedMessage}", it, true)
                            TextToSpeech.ERROR
                        }
                        if (result == TextToSpeech.ERROR) {
                            AppLog.put("tts朗读出错:$text")
                        }
                    }
                    isAddedText = true
                }
                LogUtils.d(TAG, "朗读内容添加完成")
                if (!isAddedText) {
                    playStop()
                    delay(1000)
                    completeCurrentChapter()
                }
            }
        }.onError {
            AppLog.putDebug("TTS协程异常: ${it.localizedMessage}")
        }
    }

    private fun systemVoiceForCurrentCue(): ReadAloudVoice {
        val configured = GSON.fromJsonObject<SelectItem<String>>(ReadAloud.ttsEngine)
            .getOrNull()?.value.orEmpty()
        val fallback = ReadAloudVoice(
            id = "runtime-system:$configured",
            engineType = ReadAloudVoice.ENGINE_SYSTEM,
            engineId = configured,
            speakerId = "",
            displayName = configured,
        )
        val cue = playbackQueue.cues.getOrNull(nowSpeak) ?: return fallback
        reportUnplayableVoice(cue.voice)
        val routed = SpeechVoiceRouter.route(
            cue = cue,
            supportedEngineTypes = setOf(ReadAloudVoice.ENGINE_SYSTEM),
            defaultRoute = SpeechEngineRoute(ReadAloudVoice.ENGINE_SYSTEM, configured),
        ).voice ?: fallback
        cue.voice?.takeIf { it.speakerId.isBlank() }?.let { reportBoundVoiceHasNoSpeaker(it) }
        return routed
    }

    /**
     * 绑到的音色记录本身没有 speakerId 时，[applyVoice] 会在 `ifBlank { defaultVoiceName }`
     * 处直接早退，连「音色不可用」都不会报——表现和音色名匹配失败完全一样，
     * 必须单独说清楚，否则这两种成因分不开。
     */
    private fun reportBoundVoiceHasNoSpeaker(voice: ReadAloudVoice) {
        val key = "noSpeaker:${voice.id}"
        if (key == reportedVoiceName) return
        reportedVoiceName = key
        AppLog.put(
            "绑定的音色「${voice.displayName}」没有记录具体音色（speakerId 为空），" +
                "只能用引擎出厂默认声发声\n" +
                "引擎字段：${voice.engineId.ifBlank { "（空，会落到系统全局默认引擎）" }}",
            toast = true,
        )
    }

    /**
     * 系统朗读服务只能执行系统音色，角色配的 http/cloud 音色会被静默丢成兜底音。
     * 只提示一次，避免每条 cue 都弹。
     */
    private fun reportUnplayableVoice(voice: ReadAloudVoice?) {
        if (reportedUnplayableVoice) return
        voice ?: return
        if (voice.engineType == ReadAloudVoice.ENGINE_SYSTEM) return
        reportedUnplayableVoice = true
        AppLog.put(
            "当前是系统朗读服务，角色音色「${voice.displayName}」(${voice.engineType}) 无法执行，" +
                "已改用系统兜底音；把朗读引擎切到该音色所属的引擎后重开朗读即可生效",
            toast = true,
        )
    }

    private fun applyVoice(voiceName: String) {
        val requestedName = voiceName.ifBlank { defaultVoiceName }
        if (requestedName == activeVoiceName) return
        val tts = textToSpeech ?: return
        val voice = tts.voices.orEmpty().firstOrNull { it.name == requestedName }
        if (voice == null) {
            reportVoiceUnavailable(requestedName, tts)
            return
        }
        if (tts.setVoice(voice) == TextToSpeech.SUCCESS) {
            activeVoiceName = requestedName
        } else {
            AppLog.put(
                "系统 TTS 切换音色失败：$requestedName（引擎 ${engineLabel()}）",
                toast = true,
            )
        }
    }

    /**
     * 音色没生效只会表现成「用引擎出厂默认声」，播放本身不报错。原来只写 putDebug，
     * 而 putDebug 在未开「记录日志」时整条丢弃，所以线上完全无线索可查。
     * 同名只报一次，避免每条 cue 重复弹。
     */
    private fun reportVoiceUnavailable(requestedName: String, tts: TextToSpeech) {
        if (requestedName == reportedVoiceName) return
        reportedVoiceName = requestedName
        val available = tts.voices.orEmpty().map { it.name }
        AppLog.put(
            "系统 TTS 音色不可用：「$requestedName」\n" +
                "引擎：${engineLabel()}｜出厂默认音色：${defaultVoiceName.ifBlank { "未知" }}\n" +
                "该引擎报告了 ${available.size} 个音色：" +
                available.take(30).joinToString().ifBlank { "（空）" },
            toast = true,
        )
    }

    /** 空包名意味着 TextToSpeech 绑的是系统全局默认引擎，不是 App 里选的那个。 */
    private fun engineLabel(): String = activeEngine.ifBlank { "系统默认引擎(未指定包名)" }

    private fun applyPreset(voice: ReadAloudVoice) {
        val config = runCatching {
            GSON.fromJson(voice.traitsJson, SystemTtsVoiceConfig::class.java)
        }.getOrNull() ?: SystemTtsVoiceConfig()
        val globalRate = if (ReadConfig.ttsFollowSys) {
            1f
        } else {
            (ReadConfig.ttsSpeechRate + 5) / 10f
        }
        textToSpeech?.apply {
            setSpeechRate(config.speechRate ?: globalRate)
            setPitch(config.pitch ?: 1f)
        }
    }

    override fun playStop() {
        speakSession++
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        if (ReadConfig.ttsFollowSys) {
            if (reset) {
                clearTTS()
                initTts()
            }
        } else {
            val speechRate = (speechRateSetting + 5) / 10f
            textToSpeech?.setSpeechRate(speechRate)
            upMediaMetadata()
            if (reset && !pause) {
                play()
            }
        }
    }

    /**
     * 暂停朗读
     */
    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        speakSession++
        speakJob?.cancel()
        textToSpeech?.runCatching {
            stop()
        }
    }

    /**
     * 恢复朗读
     */
    override fun resumeReadAloud() {
        super.resumeReadAloud()
        play()
    }

    override fun onPlaybackStateReplaced() {
        // 换章/重新定位后, 旧章节发言的迟到回调不得再改新队列状态
        speakSession++
    }

    /**
     * 朗读监听
     */
    private inner class TTSUtteranceListener : UtteranceProgressListener() {

        private val TAG = "TTSUtteranceListener"

        override fun onStart(s: String) {
            if (!isCurrentUtterance(s)) return
            LogUtils.d(TAG, "onStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$s")
            utteranceStartPos = paragraphStartPos
            utteranceStartReadAloudNumber = readAloudNumber
            if (isChapterTitleAt(nowSpeak)) {
                upMediaMetadata(showContent = true)
                return
            }
            readerReadAloudChapter?.let {
                if (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex)) {
                    nextParagraph(naturalCompletion = true)
                }
                if (pageIndex + 1 < it.pageCount
                    && readAloudNumber + 1 > it.pageStart(pageIndex + 1)
                ) {
                    pageIndex++
                    // This is the TTS engine advancing across a page boundary, not a user turn.
                    // Mark it so ReadBook neither detaches the session nor restarts TTS at page two.
                    withSpeechNavigation { ReadBook.moveToNextPage() }
                }
                upTtsProgress(readAloudNumber + 1)
                upMediaMetadata(showContent = true)
            }
        }

        override fun onDone(s: String) {
            if (!isCurrentUtterance(s)) return
            LogUtils.d(TAG, "onDone utteranceId:$s")
            // 章节自然完结时本章播放状态已作废, 恢复播放由换章后的 newReadAloud 负责;
            // 若此处仍用旧队列 play(), 会把本章最后一段反复重新入队
            val paragraphAdvanced = nextParagraph(naturalCompletion = true)
            if (paragraphAdvanced &&
                !pause &&
                (hasSpeechPlaybackQueue || ReadConfig.ttsParagraphInterval > 0)
            ) {
                needParagraphInterval = ReadConfig.ttsParagraphInterval > 0
                play()
            }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            super.onRangeStart(utteranceId, start, end, frame)
            if (!isCurrentUtterance(utteranceId)) return
            paragraphStartPos = utteranceStartPos + start
            if (isChapterTitleAt(nowSpeak)) return
            // 正在朗读的精确章内位置（段起点 + 段内偏移），保持 readAloudNumber 的"段起点"语义不被污染
            val position = currentRangePosition(utteranceStartReadAloudNumber, start)
            updateReadAloudProgressSnapshot(position)
            val msg =
                "onRangeStart nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId start:$start end:$end frame:$frame"
            LogUtils.d(TAG, msg)
            if (moveToReadAloudPage(position)) {
                upTtsProgress(position)
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (!isCurrentUtterance(utteranceId)) return
            LogUtils.d(
                TAG,
                "onError nowSpeak:$nowSpeak pageIndex:$pageIndex utteranceId:$utteranceId errorCode:$errorCode"
            )
            val paragraphAdvanced = nextParagraph(naturalCompletion = true)
            if (paragraphAdvanced &&
                !pause &&
                (hasSpeechPlaybackQueue || ReadConfig.ttsParagraphInterval > 0)
            ) {
                needParagraphInterval = ReadConfig.ttsParagraphInterval > 0
                play()
            }
        }

        private fun nextParagraph(naturalCompletion: Boolean = false): Boolean {
            if (hasSpeechPlaybackQueue) {
                val current = playbackCursor
                    ?: ReadAloudPlaybackCursor(nowSpeak, paragraphStartPos)
                val next = playbackQueue.next(current)
                if (next != null) {
                    moveToPlaybackCursor(next)
                    return true
                }
                if (naturalCompletion) completeCurrentChapter() else nextChapter()
                return false
            }
            //跳过全标点段落
            do {
                readAloudNumber = nextParagraphPosition(
                    currentPosition = readAloudNumber,
                    paragraphLength = contentList[nowSpeak].length,
                    paragraphStartPosition = paragraphStartPos,
                )
                paragraphStartPos = 0
                nowSpeak++
                if (nowSpeak >= contentList.size) {
                    if (naturalCompletion) completeCurrentChapter() else nextChapter()
                    return false
                }
            } while (contentList[nowSpeak].matches(AppPattern.notReadAloudRegex))
            // 页内切段不引入换行符，累加会漂移，用段落绝对位置重算
            readAloudNumber = paragraphChapterPositionAt(nowSpeak) ?: 0
            return true
        }

        @Deprecated("Deprecated in Java")
        override fun onError(s: String) {
            if (!isCurrentUtterance(s)) return
            LogUtils.d(TAG, "onError nowSpeak:$nowSpeak pageIndex:$pageIndex s:$s")
            val paragraphAdvanced = nextParagraph(naturalCompletion = true)
            if (paragraphAdvanced &&
                !pause &&
                (hasSpeechPlaybackQueue || ReadConfig.ttsParagraphInterval > 0)
            ) {
                needParagraphInterval = ReadConfig.ttsParagraphInterval > 0
                play()
            }
        }

        private fun isCurrentUtterance(utteranceId: String?): Boolean =
            utteranceId != null &&
                utteranceId == ttsUtteranceId(AppConst.APP_TAG, speakSession, nowSpeak)

    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSReadAloudService>(actionStr)
    }

}

internal fun nextParagraphPosition(
    currentPosition: Int,
    paragraphLength: Int,
    paragraphStartPosition: Int,
): Int = currentPosition + paragraphLength + 1 - paragraphStartPosition

internal fun currentRangePosition(
    utteranceStartPosition: Int,
    rangeStart: Int,
): Int = utteranceStartPosition + rangeStart

/**
 * TTS 回调只回传 utteranceId, 播放会话号必须编码进 id,
 * 否则旧会话(暂停/停止/换章后迟到)的回调无法与当前队列区分
 */
internal fun ttsUtteranceId(appTag: String, session: Int, index: Int): String =
    "$appTag$session:$index"
