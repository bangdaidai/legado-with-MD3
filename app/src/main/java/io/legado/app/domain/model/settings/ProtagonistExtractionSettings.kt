package io.legado.app.domain.model.settings

/**
 * 简介角色提取规则，可在「设置 → 阅读 → 角色提取规则」中自定义。
 *
 * 内置默认值已放宽原 [io.legado.app.help.book.ProtagonistExtractor] 的硬编码约束：
 * - 前缀覆盖「主角/男主/女主」等，并新增「配角」前缀；
 * - 允许单字名（minLength = 1），长外译名也容纳（maxLength = 8）；
 * - 分隔符补充了「/」「；」与空格。
 */
data class ProtagonistExtractionSettings(
    /** 主角名前缀正则，匹配到后取冒号后的整行作为名单。 */
    val protagonistPrefix: String = "(主角|主人公|主要角色|男主|女主|男主角|女主角)[：:]",
    /** 配角名前缀正则，匹配到后同样取冒号后的整行。 */
    val supportingPrefix: String = "(配角|主要配角|女配|男配|反派|反派角色)[：:]",
    /** 名单内的分隔符集合，用于拆分多个名字。 */
    val separators: String = "、，,/；;／\u3000 ",
    /** 名字最小长度（含），默认允许单字名。 */
    val minLength: Int = 1,
    /** 名字最大长度（含）。 */
    val maxLength: Int = 8,
    /** 无效词，按行分隔，命中即丢弃（用于过滤「主角/简介」等标签词）。 */
    val invalidWords: String = "主角\n人物\n角色\n简介\n介绍\n内容\n作者\n作品\n小说\n故事\n文章\n本书\n文案\n男\n女\n男主\n女主\n配角\n男配\n女配\n反派",
    /** 宽松模式：简介首行若含分隔符或冒号，也按名单解析（默认关闭以降低假阳性）。 */
    val relaxedFirstLine: Boolean = false,
) {
    companion object {
        val DEFAULT = ProtagonistExtractionSettings()
    }
}
