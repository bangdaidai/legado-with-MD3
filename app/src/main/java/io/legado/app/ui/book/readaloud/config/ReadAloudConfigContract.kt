package io.legado.app.ui.book.readaloud.config

import io.legado.app.domain.model.readaloud.ReadAloudContentSplitSetting

/**
 * 朗读设置页派发的意图。
 *
 * 朗读设置原来是底部弹层，宿主是阅读器，所以意图混在 [io.legado.app.ui.book.read.ReadBookIntent]
 * 里；改成 Navigation 3 整页后两个宿主（阅读页/听书页）都只经过这一个页面，
 * 读写全部落到全局的 ReadAloudPlayerViewModel，不再需要绕阅读器契约一圈。
 */
sealed interface ReadAloudConfigIntent {

    // --- 通用页签 ---

    /** [value] 取 `classic` / `player`，非法值由宿主收敛回默认。 */
    data class SetDefaultInterface(val value: String) : ReadAloudConfigIntent
    data class ShowCapsule(val value: Boolean) : ReadAloudConfigIntent
    data class CapsuleAutoCollapse(val value: Boolean) : ReadAloudConfigIntent
    data class IgnoreAudioFocus(val value: Boolean) : ReadAloudConfigIntent
    data class PauseOnPhoneCall(val value: Boolean) : ReadAloudConfigIntent
    data class WakeLock(val value: Boolean) : ReadAloudConfigIntent
    data class KeepOnExit(val value: Boolean) : ReadAloudConfigIntent
    data class MediaButtonPerNext(val value: Boolean) : ReadAloudConfigIntent
    data class AndroidMediaControl(val value: Boolean) : ReadAloudConfigIntent
    data class SystemMediaCompat(val value: Boolean) : ReadAloudConfigIntent
    data class StreamAudio(val value: Boolean) : ReadAloudConfigIntent
    data object ResetCapsulePosition : ReadAloudConfigIntent

    // --- 引擎与音色页签 ---

    data object OpenEnginesAndVoices : ReadAloudConfigIntent
    data object OpenTtsCache : ReadAloudConfigIntent
    data object OpenBookVoiceCasting : ReadAloudConfigIntent
    data object OpenSpeechStoryboard : ReadAloudConfigIntent
    data object OpenSystemTtsSettings : ReadAloudConfigIntent
    data object ClearTtsCache : ReadAloudConfigIntent

    /** 对话朗读分析模式；非规则模式需要已配置的 AI 模型。 */
    data class SetSpeechAnalysisMode(val value: String) : ReadAloudConfigIntent
    data class SetSpeechAnalysisReasoningLevel(val value: String) : ReadAloudConfigIntent
    data class SetSpeechAiPreAnalysisChapters(val value: Int) : ReadAloudConfigIntent

    /** [value] 是 [ReadAloudContentSplitSetting.encode] 的 `方式|标点` 编码。 */
    data class SetContentSplitMode(val value: String) : ReadAloudConfigIntent

    /** 多角色朗读；正在朗读时宿主需要重启朗读服务换合成管线。 */
    data class SetUseMultiSpeaker(val value: Boolean) : ReadAloudConfigIntent

    // --- 数值项（整页宿主就地铺滑块） ---

    data class SetPreDownloadNum(val value: Int) : ReadAloudConfigIntent
    data class SetPreSynthesisConcurrency(val value: Int) : ReadAloudConfigIntent
    data class SetParagraphInterval(val value: Int) : ReadAloudConfigIntent
    data class SetAudioCacheCleanTime(val value: Int) : ReadAloudConfigIntent
}
