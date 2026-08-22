package io.legado.app.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型返回的 JSON 不可靠：会包代码围栏、漏字段、少写 isRegex。
 * 这些用例锁住「坏的一条不拖垮整批、缺关键字段直接失败」的边界。
 */
class GenerateTocRuleParsingTest {

    @Test
    fun titleRulesAreParsedFromFencedJson() {
        val raw = """
            ```json
            {"rules":[
              {"name":"去站点名","pattern":"\\s*—\\s*\\S+小说网$","replacement":"","isRegex":true,"reason":"后缀"}
            ]}
            ```
        """.trimIndent()

        val rules = parseTitleCleanRules(raw)

        assertEquals(1, rules.size)
        assertEquals("去站点名", rules[0].name)
        assertEquals("", rules[0].replacement)
        assertTrue(rules[0].isRegex)
    }

    @Test
    fun emptyRuleArrayMeansTocIsAlreadyClean() {
        assertTrue(parseTitleCleanRules("""{"rules":[]}""").isEmpty())
        assertTrue(parseTitleCleanRules("""{"other":1}""").isEmpty())
    }

    @Test
    fun rulesWithoutPatternAreSkippedInsteadOfFailingTheBatch() {
        val raw = """
            {"rules":[
              {"name":"没有 pattern","replacement":""},
              {"name":"空 pattern","pattern":"  "},
              {"pattern":"广告","replacement":""}
            ]}
        """.trimIndent()

        val rules = parseTitleCleanRules(raw)

        assertEquals(1, rules.size)
        assertEquals("广告", rules[0].pattern)
        // 缺 name 时用 pattern 兜底，缺 isRegex 时按正则处理
        assertEquals("广告", rules[0].name)
        assertTrue(rules[0].isRegex)
    }

    @Test
    fun txtTocRuleKeepsBlankVolumeRule() {
        val draft = parseTxtTocRule("""{"name":"AI 正则","chapterRule":"^第.{1,9}章.*$","reason":"样本"}""")

        assertEquals("AI 正则", draft.name)
        assertEquals("^第.{1,9}章.*$", draft.chapterRule)
        assertEquals("", draft.volumeRule)
        assertEquals("样本", draft.reason)
    }

    @Test
    fun txtTocRuleWithoutChapterRuleIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            parseTxtTocRule("""{"name":"x","chapterRule":"  "}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            parseTxtTocRule("no json here")
        }
    }
}
