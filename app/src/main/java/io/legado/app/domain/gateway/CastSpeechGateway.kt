package io.legado.app.domain.gateway

import io.legado.app.help.readaloud.cast.RegexCastEffect

/**
 * 多角色效果层（[io.legado.app.help.readaloud.cast.CastSpeechOverlay]）要读的数据。
 *
 * 单独抽出来的原因不是洁癖：这一层过去同时服务朗读和分镜两种用途，却只被朗读那一侧
 * 调用——分镜页算出来的音色因此是「覆盖之前」的结果，界面上写着一个人、耳朵里是另
 * 一个的声音。把数据来源收进窄接口后，同一份逻辑可以被两侧共用，而不必让 UI 层去碰
 * `appDb`（那会被 `verifyConfigArchitecture` 的基线棘轮挡住）。
 *
 * 只剩两个读方法（正则效果 + 句级变声器）是 S8 的结果：覆盖层不再认领说话人，
 * 因此不再需要查角色表、按名字兜底两套主体 id、或就地给角色补音色——「谁说话、用哪个
 * 声音」全在分析链那一份计划里（用户分配由
 * [io.legado.app.help.readaloud.cast.CastSegmentFinalizer] 定稿进 `chapter_speech_segments`）。
 */
interface CastSpeechGateway {

    /** 这本书当前生效的正则角色效果（已解析成音色 id / 音频路径）。 */
    suspend fun regexEffects(bookUrl: String): List<RegexCastEffect>

    /** 本章按引号序号存的段级变声器预设名；没有就是空表。 */
    suspend fun quoteVoiceEffects(bookUrl: String, chapterIndex: Int): Map<Int, String>
}