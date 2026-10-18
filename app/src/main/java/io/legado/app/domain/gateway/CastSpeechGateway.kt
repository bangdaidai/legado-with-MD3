package io.legado.app.domain.gateway

import io.legado.app.data.entities.CastCharacter
import io.legado.app.help.readaloud.cast.RegexCastEffect

/**
 * 多角色朗读覆盖层（[io.legado.app.help.readaloud.cast.CastSpeechOverlay]）要读的数据。
 *
 * 单独抽出来的原因不是洁癖：这个覆盖层过去同时服务朗读和分镜两种用途，却只被朗读那一侧
 * 调用——分镜页算出来的音色因此是「分配表覆盖之前」的结果，界面上写着一个人、耳朵里是另
 * 一个的声音。把数据来源收进窄接口后，同一份覆盖逻辑可以被两侧共用，而不必让 UI 层去碰
 * `appDb`（那会被 `verifyConfigArchitecture` 的基线棘轮挡住）。
 */
interface CastSpeechGateway {

    /** 这本书当前生效的正则角色效果（已解析成音色 id / 音频路径）。 */
    suspend fun regexEffects(bookUrl: String): List<RegexCastEffect>

    /** 本章按引号序号存的段级变声器预设名；没有就是空表。 */
    suspend fun quoteVoiceEffects(bookUrl: String, chapterIndex: Int): Map<Int, String>

    /** 本书已配音的角色（含用户手动配的与 AI 自动建的）。 */
    suspend fun characters(bookUrl: String): List<CastCharacter>

    /**
     * 一个角色名在本书对应的全部音色绑定主体 id（角色行 id ∪ 同名/别名档案 id）。
     *
     * 主体 id 有两套并存（角色行 id 与人物档案 id，见
     * [io.legado.app.help.readaloud.cast.CastSubjectResolver]），绑定可能只挂在其中一侧。
     * 覆盖层查角色绑定时按这个全集兜底，避免 id 分叉时静默退回旁白。
     */
    suspend fun subjectIdsFor(bookUrl: String, name: String): List<String>

    /**
     * 角色没选音色（或音色已被删）时按声音池就近补一个，返回补选后的角色。
     *
     * 会写库：朗读和分镜都会调用，但 [io.legado.app.help.readaloud.cast.CastVoicePicker.ensureVoice]
     * 本身幂等（已选且仍存在就原样返回）。
     */
    suspend fun ensureCharacterVoice(character: CastCharacter): CastCharacter
}