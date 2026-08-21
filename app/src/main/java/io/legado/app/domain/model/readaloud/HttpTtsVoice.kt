package io.legado.app.domain.model.readaloud

/**
 * HttpTTS 引擎脚本 `voices()` 返回的单个发音人。
 *
 * 同时是朗读 URL JS 里 `voice` 变量的形状：脚本用 [id] 拼服务商的音色参数，
 * 需要服务商私有字段时读 [extraJson] 再 `JSON.parse`。
 */
data class HttpTtsVoice(
    val id: String,
    val name: String,
    val language: String = "",
    val gender: String = "",
    val style: String = "",
    val tags: List<String> = emptyList(),
    val extraJson: String = "",
)
