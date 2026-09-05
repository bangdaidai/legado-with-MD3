package io.legado.app.domain.model

import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonObject

/**
 * 可分享的 AI Skill 包。
 *
 * 一个 Skill 包是「一个任务的一条预设」的最小语义单元的集合，同时容纳两类内容：
 * - [AiSkillPackCodec.TYPE_TASK_PRESET]：任务预设（提示词 + 参数 + 运行时选项 + 模型引用）；
 * - [AiSkillPackCodec.TYPE_PROMPT_PRESET]：轻量指令预设（目前用于文本改写指令）。
 *
 * 模型只以 [AiSkillModelRef]（供应商名 + 协议 + 模型名）引用，绝不内联供应商配置本体，
 * apiKey / baseUrl / headers 永不出包。
 */
@Keep
data class AiSkillPack(
    val kind: String = AiSkillPackCodec.KIND,
    val schemaVersion: Int = AiSkillPackCodec.SCHEMA_VERSION,
    val name: String = "",
    val description: String = "",
    val items: List<AiSkillPackItem> = emptyList()
)

@Keep
data class AiSkillPackItem(
    val type: String,
    val taskType: String,
    val name: String,
    val promptTemplate: String = "",
    val instruction: String = "",
    val params: AiGenerationParams? = null,
    val runtimeOptions: AiTaskRuntimeOptions? = null,
    val modelRef: AiSkillModelRef? = null
)

@Keep
data class AiSkillModelRef(
    val providerName: String = "",
    val protocol: String = "",
    val modelName: String = "",
    val modelId: String = ""
)

/** 导入预览：解析 + 校验 + 与本地数据比对后的逐条结果 */
data class AiSkillImportPreview(
    val pack: AiSkillPack,
    val items: List<AiSkillImportItem>
)

data class AiSkillImportItem(
    val index: Int,
    val type: String,
    val taskType: String,
    val name: String,
    /** 提示词全文——预览页的一等公民，用户唯一能审的「代码」 */
    val prompt: String,
    val paramsSummary: String,
    val modelMatch: AiSkillModelMatch,
    val conflict: AiSkillImportConflict
)

data class AiSkillModelMatch(
    val status: AiSkillModelMatchStatus,
    val modelProfileId: String?,
    val providerName: String,
    val modelName: String
) {
    companion object {
        fun missing(ref: AiSkillModelRef?) = AiSkillModelMatch(
            status = AiSkillModelMatchStatus.MISSING,
            modelProfileId = null,
            providerName = ref?.providerName.orEmpty(),
            modelName = ref?.modelName.orEmpty()
        )
    }
}

enum class AiSkillModelMatchStatus { MATCHED, MISSING }

data class AiSkillImportConflict(
    /** 同名或同内容条目在本地已存在；null 表示无冲突 */
    val existingPresetId: String? = null,
    /** 内容完全一致（已导入过），导入只会产生冗余副本 */
    val sameContent: Boolean = false
)

data class AiSkillImportResult(
    val imported: Int,
    val skipped: Int
)

sealed interface AiSkillPackDecodeResult {
    data class Success(val pack: AiSkillPack) : AiSkillPackDecodeResult
    data class Failure(val reason: String) : AiSkillPackDecodeResult
}

object AiSkillPackCodec {

    const val KIND = "legado-ai-skill-pack"
    const val SCHEMA_VERSION = 1
    const val TYPE_TASK_PRESET = "task_preset"
    const val TYPE_PROMPT_PRESET = "prompt_preset"

    // 分享来的包不可信：全包与单条都设上限，防止超大文本与深嵌套 JSON 拖垮解析和 UI
    private const val MAX_PACK_CHARS = 512 * 1024
    private const val MAX_PROMPT_CHARS = 8_000
    private const val MAX_NAME_CHARS = 100
    private const val MAX_ITEMS = 64
    private const val MAX_TARGET_LANGUAGE_CHARS = 20

    private val gson = Gson()

    private val knownTaskTypes = setOf(
        AiTaskType.CHAT,
        AiTaskType.TRANSLATE_CHAPTER,
        AiTaskType.SUMMARIZE_CHAPTER,
        AiTaskType.SUMMARIZE_BOOK,
        AiTaskType.EXPLAIN_SELECTION,
        AiTaskType.CLEAN_SELECTION,
        AiTaskType.TEXT_FACTORY,
        AiTaskType.REWRITE_TEXT,
        AiTaskType.ANALYZE_SPEECH,
        AiTaskType.IDENTIFY_CHARACTERS,
        AiTaskType.BOOKSHELF_AUTO_GROUP,
        AiTaskType.AUTHOR_BIO,
        AiTaskType.TOC_RULE,
    )

    fun encode(pack: AiSkillPack): String = gson.toJson(pack)

    fun decode(text: String): AiSkillPackDecodeResult {
        if (text.isBlank()) return AiSkillPackDecodeResult.Failure("内容为空")
        if (text.length > MAX_PACK_CHARS) return AiSkillPackDecodeResult.Failure("Skill 包过大")
        // 深嵌套 JSON 会在 JsonParser 里栈溢出，这里按「不合法的包」统一兜住
        val root = try {
            JsonParser.parseString(text)
        } catch (error: Throwable) {
            return AiSkillPackDecodeResult.Failure("不是合法的 JSON：${error.localizedMessage ?: "解析失败"}")
        }
        if (!root.isJsonObject) return AiSkillPackDecodeResult.Failure("不是合法的 Skill 包")
        val obj = root.asJsonObject
        if (obj.get("kind")?.takeIf { it.isJsonPrimitive }?.asString != KIND) {
            return AiSkillPackDecodeResult.Failure("不是本应用的 Skill 包")
        }
        val schemaVersion = obj.get("schemaVersion")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asInt ?: 0
        if (schemaVersion > SCHEMA_VERSION) {
            return AiSkillPackDecodeResult.Failure("Skill 包版本过新，请先升级应用")
        }
        if (schemaVersion < 1) {
            return AiSkillPackDecodeResult.Failure("Skill 包缺少版本信息")
        }
        val rawItems = obj.getAsJsonArray("items")
            ?.takeIf { it.size() in 1..MAX_ITEMS }
            ?: return AiSkillPackDecodeResult.Failure("Skill 包没有可用内容")
        val items = rawItems.mapNotNull { element ->
            if (element.isJsonObject) sanitizeItem(element.asJsonObject) else null
        }
        if (items.isEmpty()) {
            return AiSkillPackDecodeResult.Failure("Skill 包中没有可识别的条目")
        }
        return AiSkillPackDecodeResult.Success(
            AiSkillPack(
                kind = KIND,
                schemaVersion = SCHEMA_VERSION,
                name = obj.stringOf("name").orEmpty().trim().take(MAX_NAME_CHARS),
                description = obj.stringOf("description").orEmpty().trim().take(MAX_NAME_CHARS),
                items = items
            )
        )
    }

    /** 单条不合法直接丢弃（不阻断整包），并做数值与长度钳制 */
    private fun sanitizeItem(item: JsonObject): AiSkillPackItem? {
        val type = item.stringOf("type") ?: return null
        val taskType = item.stringOf("taskType")?.takeIf { it in knownTaskTypes } ?: return null
        val name = item.stringOf("name").orEmpty().trim().take(MAX_NAME_CHARS)
            .ifBlank { return null }
        return when (type) {
            TYPE_TASK_PRESET -> {
                val prompt = item.stringOf("promptTemplate").orEmpty().trim()
                if (prompt.isBlank() || prompt.length > MAX_PROMPT_CHARS) return null
                AiSkillPackItem(
                    type = type,
                    taskType = taskType,
                    name = name,
                    promptTemplate = prompt,
                    params = parseParams(item.getAsJsonObject("params")),
                    runtimeOptions = parseRuntimeOptions(item.getAsJsonObject("runtimeOptions")),
                    modelRef = parseModelRef(item.getAsJsonObject("modelRef"))
                )
            }

            TYPE_PROMPT_PRESET -> {
                val instruction = item.stringOf("instruction").orEmpty().trim()
                if (instruction.isBlank() || instruction.length > MAX_PROMPT_CHARS) return null
                AiSkillPackItem(
                    type = type,
                    taskType = taskType,
                    name = name,
                    instruction = instruction
                )
            }

            else -> null
        }
    }

    /**
     * 手工逐字段解析生成参数，而不是反射映射到 [AiGenerationParams]：
     * GSON 的 Unsafe 构造不会执行 Kotlin 非空检查，分享来的包里未知枚举值
     * （如 reasoningLevel）会把非空字段填成 null，这里重建时全部显式钳制兜底。
     */
    private fun parseParams(obj: JsonObject?): AiGenerationParams? {
        obj ?: return null
        return AiGenerationParams(
            temperature = obj.numberOrNull("temperature")?.coerceIn(0f, 2f),
            maxOutputTokens = obj.numberOrNull("maxOutputTokens")?.toInt()?.coerceIn(1, 200_000),
            topP = obj.numberOrNull("topP")?.coerceIn(0f, 1f),
            reasoningLevel = obj.stringOf("reasoningLevel")
                ?.let { runCatching { AiReasoningLevel.valueOf(it) }.getOrNull() }
                ?: AiReasoningLevel.AUTO,
            webSearch = obj.booleanOrNull("webSearch") ?: false
        )
    }

    private fun parseRuntimeOptions(obj: JsonObject?): AiTaskRuntimeOptions? {
        obj ?: return null
        return AiTaskRuntimeOptions(
            targetLanguage = obj.stringOf("targetLanguage").orEmpty()
                .trim().take(MAX_TARGET_LANGUAGE_CHARS)
                .ifBlank { AiTaskRuntimeOptions.DEFAULT_TARGET_LANGUAGE },
            maxInputChars = obj.numberOrNull("maxInputChars")?.toInt()
                ?.coerceIn(100, 200_000) ?: AiTaskRuntimeOptions.DEFAULT_MAX_INPUT_CHARS,
            concurrentRequests = obj.numberOrNull("concurrentRequests")?.toInt()
                ?.coerceIn(1, 16) ?: AiTaskRuntimeOptions.DEFAULT_CONCURRENT_REQUESTS,
            retryCount = obj.numberOrNull("retryCount")?.toInt()
                ?.coerceIn(0, 10) ?: AiTaskRuntimeOptions.DEFAULT_RETRY_COUNT
        )
    }

    private fun parseModelRef(obj: JsonObject?): AiSkillModelRef? {
        obj ?: return null
        val modelName = obj.stringOf("modelName").orEmpty().trim()
        if (modelName.isBlank()) return null
        return AiSkillModelRef(
            providerName = obj.stringOf("providerName").orEmpty().trim(),
            protocol = obj.stringOf("protocol").orEmpty().trim(),
            modelName = modelName,
            modelId = obj.stringOf("modelId").orEmpty().trim()
        )
    }

    /** 从预设实体已有的 paramsJson / chunkPolicyJson 字符串解析出可导出的结构 */
    fun parseParamsJson(json: String?): AiGenerationParams? = runCatching {
        json?.takeIf { it.isNotBlank() }
            ?.let { JsonParser.parseString(it) }
            ?.takeIf { it.isJsonObject }
            ?.let { parseParams(it.asJsonObject) }
    }.getOrNull()

    fun parseRuntimeOptionsJson(json: String?): AiTaskRuntimeOptions? = runCatching {
        json?.takeIf { it.isNotBlank() }
            ?.let { JsonParser.parseString(it) }
            ?.takeIf { it.isJsonObject }
            ?.let { parseRuntimeOptions(it.asJsonObject) }
    }.getOrNull()

    private fun JsonObject.stringOf(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.numberOrNull(key: String): Float? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asFloat

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
}
