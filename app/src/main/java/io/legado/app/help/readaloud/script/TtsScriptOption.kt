package io.legado.app.help.readaloud.script

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.exception.NoStackTraceException
import kotlin.random.Random

/** options() 配置项类型（归一化后的子集）。 */
object TtsScriptOptionType {
    const val TEXT = "text"
    const val PASSWORD = "password"
    const val NUMBER = "number"
    const val BOOLEAN = "boolean"
    const val SELECT = "select"
    const val RANDOM_NUMBER = "random_number"

    val all = setOf(TEXT, PASSWORD, NUMBER, BOOLEAN, SELECT, RANDOM_NUMBER)
}

data class TtsScriptOptionValue(
    val label: String,
    val value: String,
)

/**
 * 脚本引擎 options() 返回的单个配置项。
 * 兼容 legado_NG 的字段别名（name→label、items/chars→values、length→digits、default→defaultValue）。
 */
data class TtsScriptOption(
    val key: String,
    val label: String = key,
    val type: String = TtsScriptOptionType.TEXT,
    val defaultValue: String = "",
    val values: List<TtsScriptOptionValue> = emptyList(),
    val digits: Int = 13,
    val allowLeadingZero: Boolean = false,
) {
    val normalizedType: String
        get() = if (type in TtsScriptOptionType.all) type else TtsScriptOptionType.TEXT

    companion object {

        internal fun parse(json: String): List<TtsScriptOption> {
            val root = runCatching { JsonParser.parseString(json) }.getOrElse {
                throw NoStackTraceException("options() 返回值不是合法 JSON: ${it.localizedMessage}")
            }
            val array = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject -> root.asJsonObject.get("options")
                    ?.takeIf { it.isJsonArray }?.asJsonArray
                else -> null
            } ?: throw NoStackTraceException("options() 返回值不是数组")
            val options = array.mapNotNull { fromJsonElement(it) }
            if (options.any { it.key.isBlank() }) {
                throw NoStackTraceException("options() 含有缺少 key 的配置项")
            }
            if (options.distinctBy { it.key }.size != options.size) {
                throw NoStackTraceException("options() 含有重复 key 的配置项")
            }
            return options
        }

        internal fun fromJsonElement(element: JsonElement): TtsScriptOption? {
            if (!element.isJsonObject) return null
            val obj = element.asJsonObject
            fun stringValue(vararg names: String): String? =
                names.firstNotNullOfOrNull { obj.stringOf(it) }?.takeIf { it.isNotBlank() }
            val key = stringValue("key") ?: return null
            val typeRaw = stringValue("type", "option_type") ?: TtsScriptOptionType.TEXT
            val type = when (typeRaw.lowercase()) {
                "password" -> TtsScriptOptionType.PASSWORD
                "number" -> TtsScriptOptionType.NUMBER
                "boolean", "bool", "switch" -> TtsScriptOptionType.BOOLEAN
                "select" -> TtsScriptOptionType.SELECT
                "random_number", "randomnumber", "random" -> TtsScriptOptionType.RANDOM_NUMBER
                else -> TtsScriptOptionType.TEXT
            }
            return TtsScriptOption(
                key = key,
                label = stringValue("label", "name") ?: key,
                type = type,
                defaultValue = stringValue("defaultValue", "default_value", "default") ?: "",
                values = obj.listOf("values", "items", "chars").orEmpty().mapNotNull { item ->
                    when {
                        item.isJsonPrimitive -> TtsScriptOptionValue(
                            label = item.asString,
                            value = item.asString,
                        )
                        item.isJsonObject -> {
                            val value = item.asJsonObject.stringOf("value")
                                ?: item.asJsonObject.stringOf("id")
                                ?: return@mapNotNull null
                            TtsScriptOptionValue(
                                label = item.asJsonObject.stringOf("label")
                                    ?: item.asJsonObject.stringOf("name") ?: value,
                                value = value,
                            )
                        }
                        else -> null
                    }
                },
                digits = (stringValue("digits", "length"))?.toIntOrNull()?.coerceIn(1, 64) ?: 13,
                allowLeadingZero = obj.booleanOf("allowLeadingZero", "allow_leading_zero") ?: false,
            )
        }

        private fun JsonObject.stringOf(name: String): String? =
            get(name)?.takeIf { it.isJsonPrimitive }?.asString

        private fun JsonObject.booleanOf(vararg names: String): Boolean? =
            names.firstNotNullOfOrNull { stringOf(it) }?.let { raw ->
                when (raw.lowercase()) {
                    "true", "1", "yes", "on" -> true
                    "false", "0", "no", "off" -> false
                    else -> null
                }
            }

        private fun JsonObject.listOf(vararg names: String): List<JsonElement>? =
            names.firstNotNullOfOrNull { key ->
                get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList()
            }
    }
}

/**
 * random_number 的稳定生成：同一引擎同一配置项生成一次后进程内复用，
 * 避免每次合成得到不同随机数导致缓存失效。
 */
internal object TtsRandomNumberStore {

    private data class Key(
        val engineId: String,
        val optionKey: String,
        val digits: Int,
        val allowLeadingZero: Boolean,
    )

    private val generated = HashMap<Key, String>()

    @Synchronized
    fun stableValue(
        engineId: String,
        optionKey: String,
        digits: Int,
        allowLeadingZero: Boolean,
    ): String {
        val key = Key(engineId, optionKey, digits, allowLeadingZero)
        return generated.getOrPut(key) { generate(key.digits, key.allowLeadingZero) }
    }

    @Synchronized
    fun regenerate(engineId: String, optionKey: String, digits: Int, allowLeadingZero: Boolean): String {
        val key = Key(engineId, optionKey, digits, allowLeadingZero)
        return generate(key.digits, key.allowLeadingZero).also { generated[key] = it }
    }

    private fun generate(digits: Int, allowLeadingZero: Boolean): String {
        val safeDigits = digits.coerceIn(1, 64)
        val builder = StringBuilder(safeDigits)
        repeat(safeDigits) { index ->
            val low = if (index == 0 && !allowLeadingZero) 1 else 0
            builder.append(Random.nextInt(low, 10))
        }
        return builder.toString()
    }
}
