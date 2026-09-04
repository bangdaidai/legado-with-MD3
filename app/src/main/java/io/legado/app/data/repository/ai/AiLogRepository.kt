package io.legado.app.data.repository.ai

import androidx.annotation.Keep
import io.legado.app.constant.PreferKey
import io.legado.app.domain.model.AiLogStep
import io.legado.app.domain.model.AiMessage
import io.legado.app.domain.model.AiMessageRole
import io.legado.app.help.config.AppConfigStore
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一条 AI 使用记录的轻量快照，用于「AI 日志」调试功能。
 *
 * 每次 app 真正发起一次 AI 调用（文本生成 / 流式生成 / 拉取模型 / 联网检索）就落一条，
 * 不会按 token 或子事件拆分，方便用户复盘「这次 AI 到底发了什么、成功没、花了多久」。
 */
@Keep
data class AiLogEntry(
    val timeMillis: Long,
    val kind: String,
    val providerName: String? = null,
    val providerProtocol: String? = null,
    val modelId: String? = null,
    val modelDisplayName: String? = null,
    val summary: String = "",
    val success: Boolean = true,
    val durationMillis: Long = 0,
    val error: String? = null,
    /** 业务场景中文名（如「作者简介生成」），由仓库层按 taskType 映射，区分于底层调用类型 [kind]。 */
    val scenario: String? = null,
    val steps: List<AiLogStep> = emptyList(),
    /**
     * 实际发给模型的全部消息（含 system 提示词、历史与工具结果），供用户对照优化提示词。
     * 截断规则见 [truncateForLog]；null 表示该次调用没有消息（如拉取模型列表）。
     */
    val prompt: String? = null,
    /**
     * 模型思考内容：流式调用聚合 [io.legado.app.domain.gateway.AiStreamEvent.Reasoning]，
     * 非流式调用取响应中的 reasoning 字段。null 表示模型没有输出思考。
     */
    val reasoning: String? = null,
    /** 模型最终输出正文，与提示词、思考三者对照看提示词效果。 */
    val output: String? = null,
)

/** 单个日志文本字段的最大保留字符数，防止完整提示词把 ai_log.json 撑爆。 */
private const val MAX_LOG_TEXT_CHARS = 12_000

/**
 * 日志文本字段超长时截断，尾部标注原始长度。
 */
internal fun String.truncateForLog(maxChars: Int = MAX_LOG_TEXT_CHARS): String {
    if (length <= maxChars) return this
    return take(maxChars) + "\n…[已截断，完整长度 $length 字符]"
}

/**
 * 把一次 AI 请求的全部消息格式化成日志里的「提示词」全文：逐条带角色标签，
 * 工具调用与调用参数原样保留。这是发给模型的真实内容（可能因截断而不完整），
 * 不是用户在设置里填的模板。
 */
fun formatAiPromptForLog(messages: List<AiMessage>): String {
    if (messages.isEmpty()) return ""
    return messages.joinToString("\n\n") { message ->
        buildString {
            append("[${message.role}]")
            if (message.role == AiMessageRole.TOOL) {
                message.name?.takeIf { it.isNotBlank() }?.let { append(" $it") }
            }
            append('\n')
            append(message.content)
            message.toolCalls.forEach { call ->
                append("\n调用工具 ${call.name}: ${call.arguments}")
            }
        }
    }.truncateForLog()
}

/**
 * 净化从 ai_log.json 恢复的条目。
 *
 * [AiLogEntry.steps] 的泛型依赖 R8 保留字段 Signature；历史版本或损坏数据可能让 steps 里
 * 混入非 [AiLogStep] 元素（例如泛型丢失后 Gson 反序列化出的 LinkedTreeMap），直接透传给 UI
 * 会在页面刷新时抛 ClassCastException。这里统一过滤异常元素，并把 Gson 可能注入的空 label 兜底为空串。
 */
internal fun sanitizeLoadedEntries(entries: List<AiLogEntry>): List<AiLogEntry> =
    entries.filterIsInstance<AiLogEntry>().map { entry ->
        entry.copy(
            steps = entry.steps.orEmpty()
                .filterIsInstance<AiLogStep>()
                .map { step ->
                    if (step.label.isNullOrEmpty()) step.copy(label = "") else step
                }
        )
    }

/**
 * 把 AI 调用记录落盘到 files 目录下的 json 文件，内存 + 文件双写，最多保留 [maxEntries] 条。
 *
 * 写入受 [PreferKey.aiLogEnabled] 总开关控制：关掉后既不读也不写，对正常路径零开销。
 * 记录动作全部包在 runCatching 里，AI 日志本身出问题绝不污染真实的 AI 调用。
 */
class AiLogRepository {

    private val file = appCtx.filesDir.resolve("ai_log.json")
    private val mutex = Mutex()
    private val maxEntries = 200
    private val loaded = AtomicBoolean(false)
    private val cache = mutableListOf<AiLogEntry>()

    /**
     * 首次访问时把文件里的历史记录读进 [cache]。
     *
     * 调用方必须已经持有 [mutex]：[Mutex] 不可重入，这里再加一次锁会永久挂住调用方
     * （record 挂住时 AI 调用永远不返回，getLogs 挂住时日志页永远转圈）。
     */
    private fun ensureLoadedLocked() {
        if (loaded.get()) return
        runCatching {
            if (file.exists()) {
                val text = file.readText()
                if (text.isNotBlank()) {
                    GSON.fromJsonArray<AiLogEntry>(text).getOrNull()?.let { entries ->
                        cache.addAll(sanitizeLoadedEntries(entries))
                    }
                }
            }
        }
        loaded.set(true)
    }

    suspend fun record(entry: AiLogEntry) {
        if (AppConfigStore.getBoolean(PreferKey.aiLogEnabled) != true) return
        runCatching {
            withContext(NonCancellable + Dispatchers.IO) {
                mutex.withLock {
                    ensureLoadedLocked()
                    cache.add(entry)
                    while (cache.size > maxEntries) cache.removeAt(0)
                    file.writeText(GSON.toJson(cache))
                }
            }
        }
    }

    suspend fun getLogs(): List<AiLogEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureLoadedLocked()
            cache.toList().reversed()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            cache.clear()
            loaded.set(true)
            runCatching { file.delete() }
        }
    }
}
