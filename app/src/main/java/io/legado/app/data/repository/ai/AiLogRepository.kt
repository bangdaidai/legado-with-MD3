package io.legado.app.data.repository.ai

import io.legado.app.constant.PreferKey
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
)

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
                    GSON.fromJsonArray<AiLogEntry>(text).getOrNull()?.let { cache.addAll(it) }
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
