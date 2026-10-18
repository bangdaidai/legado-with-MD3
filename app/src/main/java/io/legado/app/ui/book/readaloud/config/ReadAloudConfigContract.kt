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

    /** 角色声音池；配乐池与声音池原本只能从设置三层深的朗读设置 hub 进去。 */
    data object OpenVoicePool : ReadAloudConfigIntent
    data object OpenBgmPool : ReadAloudConfigIntent

    /** 对话朗读分析模式；非规则模式需要已配置的 AI 模型。 */
    data class SetSpeechAnalysisMode(val value: String) : ReadAloudConfigIntent
    data class SetSpeechAnalysisReasoningLevel(val value: String) : ReadAloudConfigIntent

    /** [value] 是 [ReadAloudContentSplitSetting.encode] 的 `方式|标点` 编码。 */
    data class SetContentSplitMode(val value: String) : ReadAloudConfigIntent

    /** 多角色朗读；正在朗读时宿主需要重启朗读服务换合成管线。 */
    data class SetUseMultiSpeaker(val value: Boolean) : ReadAloudConfigIntent

    /**
     * 多角色分配：控制正文里有没有 `<<角色（声音池）>>` 标记与角色胶囊。
     *
     * 它决定标记注不注得进去，不开就整章只有旁白；切换还要重载正文才能看到胶囊。
     */
    data class SetMultiRoleCast(val value: Boolean) : ReadAloudConfigIntent

    /**
     * 背景音乐分配：控制正文段首的 ♪ 胶囊与按场景播放配乐。
     *
     * 不开就没有 ♪ 胶囊，点胶囊进配乐弹层的主入口也就跟着断了。
     */
    data class SetBgmAssign(val value: Boolean) : ReadAloudConfigIntent

    // --- 数值项（整页宿主就地铺滑块） ---

    data class SetPreDownloadNum(val value: Int) : ReadAloudConfigIntent
    data class SetPreSynthesisConcurrency(val value: Int) : ReadAloudConfigIntent
    data class SetParagraphInterval(val value: Int) : ReadAloudConfigIntent
    data class SetAudioCacheCleanTime(val value: Int) : ReadAloudConfigIntent
}
