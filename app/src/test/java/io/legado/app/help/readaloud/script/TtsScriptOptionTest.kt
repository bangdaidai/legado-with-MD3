package io.legado.app.help.readaloud.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsScriptOptionTest {

    @Test
    fun `parses option types values and aliases`() {
        val json = """
            [
                {"key": "apiKey", "label": "API Key", "type": "password"},
                {"key": "model", "type": "select", "items": [
                    "m1",
                    {"value": "m2", "label": "模型二"}
                ]},
                {"key": "enabled", "type": "boolean", "default": "true"},
                {"key": "seed", "type": "random_number", "length": 8, "allowLeadingZero": true},
                {"key": "legacy", "name": "旧名", "option_type": "number"}
            ]
        """.trimIndent()
        val options = TtsScriptOption.parse(json)
        assertEquals(5, options.size)
        assertEquals(TtsScriptOptionType.PASSWORD, options[0].normalizedType)
        assertEquals("API Key", options[0].label)
        val select = options[1]
        assertEquals(TtsScriptOptionType.SELECT, select.normalizedType)
        assertEquals(2, select.values.size)
        assertEquals("m1", select.values[0].value)
        assertEquals("m1", select.values[0].label)
        assertEquals("m2", select.values[1].value)
        assertEquals("模型二", select.values[1].label)
        assertEquals(TtsScriptOptionType.BOOLEAN, options[2].normalizedType)
        assertEquals("true", options[2].defaultValue)
        assertEquals(TtsScriptOptionType.RANDOM_NUMBER, options[3].normalizedType)
        assertEquals(8, options[3].digits)
        assertTrue(options[3].allowLeadingZero)
        assertEquals(TtsScriptOptionType.NUMBER, options[4].normalizedType)
        assertEquals("旧名", options[4].label)
    }

    @Test
    fun `duplicate or missing keys throw`() {
        val duplicate = """[{"key": "a"}, {"key": "a"}]"""
        val error = runCatching { TtsScriptOption.parse(duplicate) }.exceptionOrNull()
        assertTrue(error!!.message!!.contains("重复"))

        val missing = """[{"label": "no key"}]"""
        val error2 = runCatching { TtsScriptOption.parse(missing) }.exceptionOrNull()
        assertTrue(error2!!.message!!.contains("key"))
    }

    @Test
    fun `random number is stable and respects digits`() {
        val first = TtsRandomNumberStore.stableValue("engine", "seed", 13, false)
        assertEquals(13, first.length)
        assertEquals(first, TtsRandomNumberStore.stableValue("engine", "seed", 13, false))
        assertTrue(!first.startsWith("0"))
        val other = TtsRandomNumberStore.stableValue("engine", "seed2", 4, true)
        assertEquals(4, other.length)
    }

    @Test
    fun `regenerate replaces stable value`() {
        TtsRandomNumberStore.stableValue("engine2", "k", 10, false)
        val regenerated = TtsRandomNumberStore.regenerate("engine2", "k", 10, false)
        assertEquals(10, regenerated.length)
        // 稳定值在 regenerate 后跟随新值
        assertEquals(regenerated, TtsRandomNumberStore.stableValue("engine2", "k", 10, false))
    }
}
