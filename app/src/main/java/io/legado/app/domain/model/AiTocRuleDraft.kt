package io.legado.app.domain.model

/**
 * AI 依据真实章节标题给出的一条标题净化规则草稿。
 *
 * 是纯值对象而不是 [io.legado.app.data.entities.ReplaceRule]：AI 产出的正则可能写错、
 * 可能命中过多，必须先在真实目录上预览命中效果，用户确认后才落库。
 */
data class AiTitleCleanRuleDraft(
    val name: String,
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean,
    /** 模型说明这条规则针对什么噪声，展示给用户判断是否采用。 */
    val reason: String,
)

/**
 * AI 依据 TXT 正文行给出的目录正则草稿。[volumeRule] 可为空表示这本书没有卷。
 */
data class AiTxtTocRuleDraft(
    val name: String,
    val chapterRule: String,
    val volumeRule: String,
    val reason: String,
)
