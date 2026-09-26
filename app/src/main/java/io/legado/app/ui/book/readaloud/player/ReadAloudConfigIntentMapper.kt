package io.legado.app.ui.book.readaloud.player

import io.legado.app.ui.book.readaloud.config.ReadAloudConfigIntent

/**
 * 把朗读设置页的 [ReadAloudConfigIntent] 翻译成全局设置写入或跳页 Effect。
 *
 * 设置页是 Navigation 3 整页，阅读页与听书页都跳进同一个目的地，
 * 所以写入统一落到 [ReadAloudPlayerViewModel.onConfigIntent]，
 * 跳页经 Effect 交给路由层，由 MainNavGraph 执行导航。
 */
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
        is ReadAloudConfigIntent.SetSpeechAiPreAnalysisChapters ->
            onConfigIntent(ReadAloudConfigOption.SpeechAiPreAnalysisChapters, value = intent.value)

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

        // 跳独立整页：发 Effect，由路由层收集后交给 MainNavGraph 导航。
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
