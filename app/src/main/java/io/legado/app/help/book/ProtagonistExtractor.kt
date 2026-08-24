package io.legado.app.help.book

import io.legado.app.domain.model.settings.ProtagonistExtractionSettings

/**
 * 从书籍简介中提取角色名（主角 / 配角）。
 *
 * 提取规则完全由 [ProtagonistExtractionSettings] 驱动，可在
 * 「设置 → 阅读 → 角色提取规则」中自定义，以适应不同书源简介写法。
 * 提取结果写入 [io.legado.app.data.entities.BookCharacterProfile]
 * （由调用方负责落库与标记主角/配角）。
 */
object ProtagonistExtractor {

    data class ExtractedCharacter(
        val name: String,
        val isProtagonist: Boolean,
        val voiceGender: String = GENDER_UNKNOWN,
    )

    private const val GENDER_MALE = "male"
    private const val GENDER_FEMALE = "female"
    private const val GENDER_UNKNOWN = "unknown"

    /** 按命中前缀词推断性别，便于听书多角色朗读直接复用音色。 */
    private fun genderFromPrefix(label: String): String =
        when {
            "男" in label -> GENDER_MALE
            "女" in label -> GENDER_FEMALE
            else -> GENDER_UNKNOWN
        }

    /**
     * 从简介提取角色名列表。
     * @param rules 提取规则，缺省用 [ProtagonistExtractionSettings.DEFAULT]。
     */
    fun extract(
        intro: String?,
        rules: ProtagonistExtractionSettings = ProtagonistExtractionSettings.DEFAULT,
    ): List<ExtractedCharacter> {
        if (intro.isNullOrBlank()) return emptyList()

        val invalid = rules.invalidWords
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val separatorRegex = Regex("[${Regex.escape(rules.separators)}]")
        val protagonistRegex = regexOf(rules.protagonistPrefix)
        val supportingRegex = regexOf(rules.supportingPrefix)

        val result = LinkedHashMap<String, ExtractedCharacter>()

        fun consume(block: String, isProtagonist: Boolean, gender: String) {
            for (raw in block.split(separatorRegex)) {
                val name = clean(raw, invalid, rules) ?: continue
                val existing = result[name]
                if (existing == null) {
                    result[name] = ExtractedCharacter(name, isProtagonist, gender)
                } else {
                    // 同一名字若先被识别为配角、后又命中主角前缀，以主角优先。
                    val nextProtagonist = if (isProtagonist) true else existing.isProtagonist
                    // 已有性别推断则保留，避免被 unknown 覆盖。
                    val nextGender = if (existing.voiceGender != GENDER_UNKNOWN) existing.voiceGender else gender
                    result[name] = existing.copy(isProtagonist = nextProtagonist, voiceGender = nextGender)
                }
            }
        }

        protagonistRegex.findAll(intro).forEach { m ->
            consume(blockAfter(intro, m), true, genderFromPrefix(m.groupValues.getOrNull(1).orEmpty()))
        }
        supportingRegex.findAll(intro).forEach { m ->
            consume(blockAfter(intro, m), false, genderFromPrefix(m.groupValues.getOrNull(1).orEmpty()))
        }

        if (result.isEmpty() && rules.relaxedFirstLine) {
            val firstLine = intro.lines().firstOrNull { it.isNotBlank() } ?: return result.values.toList()
            val hasSeparator = rules.separators.any { it in firstLine } ||
                '：' in firstLine || ':' in firstLine
            if (hasSeparator) consume(firstLine, false, GENDER_UNKNOWN)
        }

        return result.values.toList()
    }

    /**
     * 宽松模式：忽略前缀，仅用简介首行按分隔符解析。
     * 等价于 [extract] 在 [ProtagonistExtractionSettings.relaxedFirstLine] = true 时的行为。
     */
    fun extractRelaxed(
        intro: String?,
        rules: ProtagonistExtractionSettings = ProtagonistExtractionSettings.DEFAULT,
    ): List<ExtractedCharacter> = extract(intro, rules.copy(relaxedFirstLine = true))

    private fun regexOf(pattern: String): Regex =
        if (pattern.isBlank()) Regex("$.^") else runCatching { Regex(pattern) }.getOrElse { Regex("$.^") }

    /** 取匹配位置之后、直到行尾的名单文本。 */
    private fun blockAfter(intro: String, match: MatchResult): String {
        val start = match.range.last + 1
        if (start >= intro.length) return ""
        val rest = intro.substring(start)
        val newLine = rest.indexOf('\n')
        return if (newLine >= 0) rest.substring(0, newLine) else rest
    }

    /**
     * 清洗单个候选词：
     * 1. 去掉首尾标点与空白；
     * 2. 去掉可能带在名字前的内置角色标签（如「男主张三」→「张三」）；
     * 3. 过滤空白、无效词与长度越界。
     */
    private fun clean(raw: String, invalid: Set<String>, rules: ProtagonistExtractionSettings): String? {
        var s = raw.trim()
            .replace(Regex("^[\\p{P}\\p{Z}]+|[\\p{P}\\p{Z}]+$"), "")
        s = s.replace(Regex("^(主角|配角|男主|女主|男配|女配|反派|主人公|主要角色)[：:]?"), "")
        s = s.trim()
            .replace(Regex("^[\\p{P}\\p{Z}]+|[\\p{P}\\p{Z}]+$"), "")
        if (s.isEmpty()) return null
        if (s in invalid) return null
        if (s.any { it.isWhitespace() }) return null
        if (s.length !in rules.minLength..rules.maxLength) return null
        return s
    }
}
