package io.legado.app.ui.main.my.authorManage

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import io.legado.app.help.config.AppConfigStore
import org.json.JSONObject

/**
 * 作者自定义简介的持久化存储。
 * 以单个 JSON 对象（authorName -> intro）存放在 AppConfigStore 中，
 * 以便在作者列表与作者详情之间共享同一份数据并支持响应式更新。
 */
object AuthorProfileStore {

    private const val KEY = "author_intros"

    fun getBio(name: String): String {
        val json = AppConfigStore.getString(KEY) ?: return ""
        return runCatching { JSONObject(json).optString(name, "") }.getOrDefault("")
    }

    fun observeBios(): Flow<Map<String, String>> =
        AppConfigStore.observeString(KEY).map { json ->
            if (json.isNullOrBlank()) emptyMap()
            else runCatching { JSONObject(json).toMap() }.getOrDefault(emptyMap())
        }

    fun saveBio(name: String, bio: String) {
        val obj = JSONObject()
        val existing = AppConfigStore.getString(KEY)
        if (!existing.isNullOrBlank()) {
            runCatching { JSONObject(existing).toMap() }
                .getOrDefault(emptyMap())
                .forEach { (k, v) -> obj.put(k, v) }
        }
        obj.put(name, bio)
        AppConfigStore.putString(KEY, obj.toString())
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
