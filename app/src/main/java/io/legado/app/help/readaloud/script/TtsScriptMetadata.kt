package io.legado.app.help.readaloud.script

import io.legado.app.data.entities.HttpTTS

/**
 * 脚本引擎判别与脚本头元数据。
 * 头部注释约定与 legado_NG 一致：`// @key value`。
 */
fun HttpTTS.isScriptEngine(): Boolean = !script.isNullOrBlank()

data class TtsScriptMetadata(
    val uuid: String = "",
    val name: String = "",
    val version: String = "",
    val audioType: String = "",
    val sampleText: String = "",
    val capabilities: List<String> = emptyList(),
    val enabled: Boolean = true,
    val cookieJar: Boolean = false,
    val maxConcurrency: Int = 0,
) {
    fun supportsCapability(capability: String): Boolean = capability in capabilities

    companion object {

        private val metaRegex = Regex("""^\s*//\s*@(\w+)\s*(.*)$""")

        /** 解析脚本头部 `// @key value` 元信息；@capabilities 为逗号分隔列表。 */
        fun parse(script: String?): TtsScriptMetadata {
            if (script.isNullOrBlank()) return TtsScriptMetadata()
            val values = LinkedHashMap<String, String>()
            script.lineSequence().forEach { line ->
                val match = metaRegex.find(line) ?: return@forEach
                values[match.groupValues[1].lowercase()] = match.groupValues[2].trim()
            }
            return TtsScriptMetadata(
                uuid = values["uuid"].orEmpty(),
                name = values["name"].orEmpty(),
                version = values["version"].orEmpty(),
                audioType = values["audiotype"].orEmpty(),
                sampleText = values["sampletext"].orEmpty(),
                capabilities = values["capabilities"].orEmpty()
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() },
                enabled = parseBool(values["enabled"], default = true),
                cookieJar = parseBool(values["cookiejar"], default = false),
                maxConcurrency = values["maxconcurrency"]?.toIntOrNull() ?: 0,
            )
        }

        private fun parseBool(raw: String?, default: Boolean): Boolean {
            return when (raw?.trim()?.lowercase()) {
                null, "" -> default
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> default
            }
        }
    }
}
