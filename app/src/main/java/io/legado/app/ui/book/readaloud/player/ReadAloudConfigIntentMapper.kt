package io.legado.app.ui.book.readaloud.player

import io.legado.app.ui.book.read.ReadAloudSubPage
import io.legado.app.ui.book.read.ReadBookIntent
import io.legado.app.ui.book.readaloud.config.ReadAloudConfigIntent

internal enum class ReadAloudPlayerConfigHostAction {
    OpenTtsEnginesAndVoices,
    OpenTtsCache,
    OpenBookVoiceCasting,
    OpenVoicePool,
    OpenBgmPool,
    OpenCapsuleStyle,
    OpenSystemTtsSettings,
    OpenPreDownloadNumPicker,
    OpenPreSynthesisConcurrencyPicker,
    OpenParagraphIntervalPicker,
    OpenCacheCleanTimePicker,
}

/**
 * 为 `ReadAloudConfigContent` 发出的每一个导航选项，返回对应的播放器宿主操作。
 *
 * 共享内容使用的是 [ReadBookIntent]，而播放器是一个 Activity 级别的浮层，
 * 没有阅读器的 ViewModel。将这个桥接逻辑保持为纯函数，
 * 可以让这些点击事件被转发出去，而不是落到设置适配器里。
 */
internal fun ReadBookIntent.toReadAloudPlayerConfigHostAction():
        ReadAloudPlayerConfigHostAction? =
    when (this) {
        ReadBookIntent.OpenTtsEnginesAndVoices ->
            ReadAloudPlayerConfigHostAction.OpenTtsEnginesAndVoices

        ReadBookIntent.OpenTtsCache -> ReadAloudPlayerConfigHostAction.OpenTtsCache
        ReadBookIntent.OpenBookVoiceCasting ->
            ReadAloudPlayerConfigHostAction.OpenBookVoiceCasting

        // 整屏子页共用一条意图，这里再拆成播放器宿主的三个动作
        is ReadBookIntent.OpenReadAloudSubPage -> when (page) {
            ReadAloudSubPage.VoicePool -> ReadAloudPlayerConfigHostAction.OpenVoicePool
            ReadAloudSubPage.BgmPool -> ReadAloudPlayerConfigHostAction.OpenBgmPool
            ReadAloudSubPage.CapsuleStyle -> ReadAloudPlayerConfigHostAction.OpenCapsuleStyle
        }

        ReadBookIntent.OpenSystemTtsSettings ->
            ReadAloudPlayerConfigHostAction.OpenSystemTtsSettings

        ReadBookIntent.OpenPreDownloadNumPicker ->
            ReadAloudPlayerConfigHostAction.OpenPreDownloadNumPicker

        ReadBookIntent.OpenPreSynthesisConcurrencyPicker ->
            ReadAloudPlayerConfigHostAction.OpenPreSynthesisConcurrencyPicker

        ReadBookIntent.OpenParagraphIntervalPicker ->
            ReadAloudPlayerConfigHostAction.OpenParagraphIntervalPicker

        ReadBookIntent.OpenCacheCleanTimePicker ->
            ReadAloudPlayerConfigHostAction.OpenCacheCleanTimePicker

        else -> null
    }

/**
 * 把配置卡片的 [ReadBookIntent] 分发为全局设置写入、播放器命令或宿主动作。
 *
 * 配置内容（`ReadAloudConfigContent`）的契约是 `ReadBookIntent`，因为它的主宿主是阅读器。
 * 听书播放弹层是全局浮层、没有阅读器 ViewModel，所以这里把同一批意图落到
 * [ReadAloudPlayerViewModel.onConfigIntent] 或播放器宿主，两个宿主的设置语义完全一致。
 */
internal fun ReadAloudPlayerViewModel.applyReadBookConfigIntent(
    intent: ReadBookIntent,
    onHostAction: (ReadAloudPlayerConfigHostAction) -> Unit,
) {
    intent.toReadAloudPlayerConfigHostAction()?.let {
        onHostAction(it)
        return
    }
    when (intent) {
        is ReadBookIntent.SetDefaultReadAloudInterface ->
            onConfigIntent(ReadAloudConfigOption.DefaultInterface, value = intent.value)

        is ReadBookIntent.SetShowReadAloudCapsule ->
            onConfigIntent(ReadAloudConfigOption.ShowCapsule, selected = intent.value)

        is ReadBookIntent.SetCapsuleAutoCollapse ->
            onConfigIntent(ReadAloudConfigOption.CapsuleAutoCollapse, selected = intent.value)

        is ReadBookIntent.SetReadAloudIgnoreAudioFocus ->
            onConfigIntent(ReadAloudConfigOption.IgnoreAudioFocus, selected = intent.value)

        is ReadBookIntent.SetReadAloudPauseOnPhoneCall ->
            onConfigIntent(ReadAloudConfigOption.PauseOnPhoneCall, selected = intent.value)

        is ReadBookIntent.SetReadAloudWakeLock ->
            onConfigIntent(ReadAloudConfigOption.WakeLock, selected = intent.value)

        is ReadBookIntent.SetReadAloudKeepOnExit ->
            onConfigIntent(ReadAloudConfigOption.KeepOnExit, selected = intent.value)

        is ReadBookIntent.SetReadAloudMediaButtonPerNext ->
            onConfigIntent(ReadAloudConfigOption.MediaButtonPerNext, selected = intent.value)

        is ReadBookIntent.SetReadAloudAndroidMediaControl ->
            onConfigIntent(ReadAloudConfigOption.AndroidMediaControl, selected = intent.value)

        is ReadBookIntent.SetReadAloudSystemMediaCompat ->
            onConfigIntent(ReadAloudConfigOption.SystemMediaCompat, selected = intent.value)

        is ReadBookIntent.SetReadAloudStreamAudio ->
            onConfigIntent(ReadAloudConfigOption.StreamAudio, selected = intent.value)

        is ReadBookIntent.SetSpeechAnalysisMode ->
            onConfigIntent(ReadAloudConfigOption.SpeechAnalysisMode, value = intent.value)

        is ReadBookIntent.SetSpeechAnalysisReasoningLevel ->
            onConfigIntent(ReadAloudConfigOption.SpeechAnalysisReasoningLevel, value = intent.value)

        is ReadBookIntent.SetUseMultiSpeaker ->
            onConfigIntent(ReadAloudConfigOption.UseMultiSpeaker, selected = intent.value)

        is ReadBookIntent.SetMultiRoleCast ->
            onConfigIntent(ReadAloudConfigOption.MultiRoleCast, selected = intent.value)

        is ReadBookIntent.SetBgmAssign ->
            onConfigIntent(ReadAloudConfigOption.BgmAssign, selected = intent.value)

        is ReadBookIntent.SetReadAloudContentSplitMode ->
            onConfigIntent(ReadAloudConfigOption.ContentSplit, value = intent.value)

        is ReadBookIntent.ApplyPreDownloadNum ->
            onConfigIntent(ReadAloudConfigOption.PreDownloadNum, intValue = intent.value)

        is ReadBookIntent.ApplyPreSynthesisConcurrency ->
            onConfigIntent(ReadAloudConfigOption.PreSynthesisConcurrency, intValue = intent.value)

        is ReadBookIntent.ApplyParagraphInterval ->
            onConfigIntent(ReadAloudConfigOption.ParagraphInterval, intValue = intent.value)

        is ReadBookIntent.ApplyAudioCacheCleanTime ->
            onConfigIntent(ReadAloudConfigOption.AudioCacheCleanTime, intValue = intent.value)

        ReadBookIntent.ResetReadAloudCapsulePosition -> resetCapsulePosition()
        ReadBookIntent.ClearTtsCache -> clearTtsCache()
        else -> Unit
    }
}

/**
 * 「听书配置」全页（Navigation 3 目的地）的 [ReadAloudConfigIntent] 分发到全局设置写入与页面 Effect。
 *
 * 配置全页与弹层共享同一个媒体设置，设置写入统一落到 [ReadAloudPlayerViewModel.onConfigIntent]；
 * 页面级 Effect 交由路由层收集后交给 MainNavGraph 执行导航。
 */

/**
 * fork 配置契约与 upstream 配置内容（ReadBookIntent 契约）的桥接：
 * 配置页内容组件已被替换为上游版本，需要把 fork 的 [ReadAloudConfigIntent]
 * 翻译到对应的 [ReadBookIntent]；没有对应项时返回 null（该设置项在全页视图暂不可达）。
 */
internal fun ReadAloudConfigIntent.toReadBookIntent(): ReadBookIntent? = when (this) {
    is ReadAloudConfigIntent.SetDefaultInterface -> ReadBookIntent.SetDefaultReadAloudInterface(value)
    is ReadAloudConfigIntent.ShowCapsule -> ReadBookIntent.SetShowReadAloudCapsule(value)
    is ReadAloudConfigIntent.IgnoreAudioFocus -> ReadBookIntent.SetReadAloudIgnoreAudioFocus(value)
    is ReadAloudConfigIntent.PauseOnPhoneCall -> ReadBookIntent.SetReadAloudPauseOnPhoneCall(value)
    is ReadAloudConfigIntent.WakeLock -> ReadBookIntent.SetReadAloudWakeLock(value)
    is ReadAloudConfigIntent.KeepOnExit -> ReadBookIntent.SetReadAloudKeepOnExit(value)
    is ReadAloudConfigIntent.MediaButtonPerNext -> ReadBookIntent.SetReadAloudMediaButtonPerNext(value)
    is ReadAloudConfigIntent.AndroidMediaControl -> ReadBookIntent.SetReadAloudAndroidMediaControl(value)
    is ReadAloudConfigIntent.SystemMediaCompat -> ReadBookIntent.SetReadAloudSystemMediaCompat(value)
    is ReadAloudConfigIntent.StreamAudio -> ReadBookIntent.SetReadAloudStreamAudio(value)
    is ReadAloudConfigIntent.SetContentSplitMode -> ReadBookIntent.SetReadAloudContentSplitMode(value)
    ReadAloudConfigIntent.OpenEnginesAndVoices -> ReadBookIntent.OpenTtsEnginesAndVoices
    ReadAloudConfigIntent.OpenTtsCache -> ReadBookIntent.OpenTtsCache
    ReadAloudConfigIntent.OpenBookVoiceCasting -> ReadBookIntent.OpenBookVoiceCasting
    ReadAloudConfigIntent.OpenSystemTtsSettings -> ReadBookIntent.OpenSystemTtsSettings
    else -> null
}

internal fun ReadAloudPlayerViewModel.applyReadAloudConfigIntent(intent: ReadAloudConfigIntent) {
    when (intent) {
        is ReadAloudConfigIntent.SetDefaultInterface ->
            onConfigIntent(ReadAloudConfigOption.DefaultInterface, value = intent.value)

        is ReadAloudConfigIntent.ShowCapsule ->
            onConfigIntent(ReadAloudConfigOption.ShowCapsule, selected = intent.value)

        is ReadAloudConfigIntent.CapsuleAutoCollapse ->
            onConfigIntent(ReadAloudConfigOption.CapsuleAutoCollapse, selected = intent.value)

        is ReadAloudConfigIntent.IgnoreAudioFocus ->
            onConfigIntent(ReadAloudConfigOption.IgnoreAudioFocus, selected = intent.value)

        is ReadAloudConfigIntent.PauseOnPhoneCall ->
            onConfigIntent(ReadAloudConfigOption.PauseOnPhoneCall, selected = intent.value)

        is ReadAloudConfigIntent.WakeLock ->
            onConfigIntent(ReadAloudConfigOption.WakeLock, selected = intent.value)

        is ReadAloudConfigIntent.KeepOnExit ->
            onConfigIntent(ReadAloudConfigOption.KeepOnExit, selected = intent.value)

        is ReadAloudConfigIntent.MediaButtonPerNext ->
            onConfigIntent(ReadAloudConfigOption.MediaButtonPerNext, selected = intent.value)

        is ReadAloudConfigIntent.AndroidMediaControl ->
            onConfigIntent(ReadAloudConfigOption.AndroidMediaControl, selected = intent.value)

        is ReadAloudConfigIntent.SystemMediaCompat ->
            onConfigIntent(ReadAloudConfigOption.SystemMediaCompat, selected = intent.value)

        is ReadAloudConfigIntent.StreamAudio ->
            onConfigIntent(ReadAloudConfigOption.StreamAudio, selected = intent.value)

        is ReadAloudConfigIntent.SetSpeechAnalysisMode ->
            onConfigIntent(ReadAloudConfigOption.SpeechAnalysisMode, value = intent.value)

        is ReadAloudConfigIntent.SetSpeechAnalysisReasoningLevel ->
            onConfigIntent(ReadAloudConfigOption.SpeechAnalysisReasoningLevel, value = intent.value)

        is ReadAloudConfigIntent.SetUseMultiSpeaker ->
            onConfigIntent(ReadAloudConfigOption.UseMultiSpeaker, selected = intent.value)

        is ReadAloudConfigIntent.SetContentSplitMode ->
            onConfigIntent(ReadAloudConfigOption.ContentSplit, value = intent.value)

        is ReadAloudConfigIntent.SetPreDownloadNum ->
            onConfigIntent(ReadAloudConfigOption.PreDownloadNum, intValue = intent.value)

        is ReadAloudConfigIntent.SetPreSynthesisConcurrency ->
            onConfigIntent(ReadAloudConfigOption.PreSynthesisConcurrency, intValue = intent.value)

        is ReadAloudConfigIntent.SetParagraphInterval ->
            onConfigIntent(ReadAloudConfigOption.ParagraphInterval, intValue = intent.value)

        is ReadAloudConfigIntent.SetAudioCacheCleanTime ->
            onConfigIntent(ReadAloudConfigOption.AudioCacheCleanTime, intValue = intent.value)

        ReadAloudConfigIntent.ResetCapsulePosition ->
            onConfigIntent(ReadAloudConfigOption.ResetCapsulePosition)

        // 以下为全页面 Effect，由路由层收集后交给 MainNavGraph 执行导航。
        ReadAloudConfigIntent.OpenEnginesAndVoices ->
            effect(ReadAloudPlayerEffect.OpenEnginesAndVoices(uiState.value.bookUrl.ifBlank { null }))

        ReadAloudConfigIntent.OpenTtsCache ->
            effect(ReadAloudPlayerEffect.OpenTtsCache)

        ReadAloudConfigIntent.OpenBookVoiceCasting -> {
            val bookUrl = uiState.value.bookUrl
            if (bookUrl.isNotBlank()) effect(ReadAloudPlayerEffect.OpenBookVoiceCasting(bookUrl))
        }

        ReadAloudConfigIntent.OpenSpeechStoryboard -> {
            val bookUrl = uiState.value.bookUrl
            if (bookUrl.isNotBlank()) effect(ReadAloudPlayerEffect.OpenSpeechStoryboard(bookUrl))
        }

        ReadAloudConfigIntent.OpenSystemTtsSettings ->
            effect(ReadAloudPlayerEffect.OpenSystemTtsSettings)

        ReadAloudConfigIntent.ClearTtsCache -> clearTtsCache()
    }
}
