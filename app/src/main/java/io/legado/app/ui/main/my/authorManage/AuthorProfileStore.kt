package io.legado.app.ui.main.my.authorManage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.config.compatDsString
import org.json.JSONObject

/**
 * 作者自定义简介的持久化存储。
 * 以单个 JSON 对象（authorName -> intro）存放在 AppConfigStore 中，
 * 以便在作者列表与作者详情之间共享同一份数据并支持响应式更新。
 */
object AuthorProfileStore {

    private const val KEY = "author_intros"

    fun observeBios(): Flow<Map<String, String>> =
        AppConfigStore.observeString(KEY).map { parseBios(it) }

    /** 读改写在 AppConfigStore 的临界区内完成，避免并发保存互相覆盖。 */
    fun saveBio(name: String, bio: String) {
        AppConfigStore.atomicUpdate(
            read = { it.compatDsString(KEY) },
            toPrefMap = { json -> mapOf(KEY to json) },
            transform = { json ->
                val bios = parseBios(json).toMutableMap()
                if (bio.isBlank()) bios.remove(name) else bios[name] = bio
                JSONObject(bios).toString()
            },
        )
    }

    private fun parseBios(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching { JSONObject(json).toMap() }.getOrDefault(emptyMap())
    }

    private fun JSONObject.toMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val keys = keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = optString(k, "")
        }
        return map
    }
}
