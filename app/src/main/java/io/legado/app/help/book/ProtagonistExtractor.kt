package io.legado.app.help.book

/**
 * 从书籍简介中自动提取主角名。
 * 移植自 readdai 项目，纯 Kotlin 工具，无 UI/框架依赖。
 *
 * 三种设主角途径：
 * 1. 简介自动提取（本工具）
 * 2. 阅读页长按文本设为主角（见阅读页菜单接线）
 * 3. 知识-人物编辑页「是否主角」开关（见 BookInfoEdit）
 *
 * 数据源统一写入 book_character_profiles.isProtagonist 列。
 */
object ProtagonistExtractor {

    /** 主角提取正则：匹配「主角：xxx」或「主角:xxx」 */
    private val PROTAGONIST_REGEX = Regex("主角[：:](.+)")

    /** 中文标点分隔符 */
    private val NAME_SEPARATOR_REGEX = Regex("[、，,]")

    /** 无效词过滤（非人名的常见后缀/前缀） */
    private val INVALID_WORDS = setOf(
        "主角", "人物", "角色", "简介", "介绍", "内容",
        "作者", "作品", "小说", "故事", "文章", "本书", "文案",
        "男", "女", "男主", "女主",
    )

    /** 主角名有效长度范围（含） */
    private const val MIN_NAME_LENGTH = 2
    private const val MAX_NAME_LENGTH = 4

    /**
     * 从简介文本中提取主角名列表。
     * @param intro 书籍简介文本
     * @return 去重后的主角名列表，失败时返回空列表
     */
    fun extract(intro: String?): List<String> {
        if (intro.isNullOrBlank()) return emptyList()

        val matchResult = PROTAGONIST_REGEX.find(intro) ?: return emptyList()
        val namesBlock = matchResult.groupValues.getOrNull(1) ?: return emptyList()

        return namesBlock
            .split(NAME_SEPARATOR_REGEX)
            .map { it.trim() }
            .filter { name ->
                name.length in MIN_NAME_LENGTH..MAX_NAME_LENGTH &&
                name !in INVALID_WORDS &&
                name.none { c -> c.isWhitespace() }
            }
            .distinct()
    }

    /**
     * 宽松模式：也尝试从文本开头几行提取人名（不以「主角：」前缀的）。
     * 作为 extract 的补充，用于简介不含明确「主角：」标记但开头几行直接列人名的场景。
     */
    fun extractRelaxed(intro: String?): List<String> {
        return extract(intro).ifEmpty {
            intro?.let { text ->
                val firstLine = text.lines().firstOrNull { it.isNotBlank() } ?: return emptyList()
                // 首行按分隔符拆分，过滤短词
                firstLine.split(NAME_SEPARATOR_REGEX)
                    .map { it.trim() }
                    .filter { name ->
                        name.length in MIN_NAME_LENGTH..MAX_NAME_LENGTH &&
                        name !in INVALID_WORDS &&
                        name.none { c -> c.isWhitespace() }
                    }
                    .distinct()
            } ?: emptyList()
        }
    }
}
