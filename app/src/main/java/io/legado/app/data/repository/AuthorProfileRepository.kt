package io.legado.app.data.repository

import io.legado.app.data.dao.AuthorProfileDao
import io.legado.app.data.entities.AuthorProfile
import io.legado.app.help.config.AppConfigStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

class AuthorProfileRepository(private val dao: AuthorProfileDao) {

    /** 作者名 -> 简介记录 */
    fun observeProfiles(): Flow<Map<String, AuthorProfile>> =
        dao.getAll().map { list -> list.associateBy { it.name } }

    /** 用户手写的简介；清空内容等于删除该条记录。 */
    suspend fun saveManualBio(name: String, bio: String) {
        if (bio.isBlank()) {
            dao.deleteByName(name)
            return
        }
        dao.upsert(
            AuthorProfile(
                name = name,
                bio = bio,
                source = AuthorProfile.SOURCE_MANUAL,
                updateTime = System.currentTimeMillis(),
            )
        )
    }

    /** AI 生成的简介，带上所用模型便于事后追溯。 */
    suspend fun saveAiBio(name: String, bio: String, model: String?) {
        if (bio.isBlank()) return
        dao.upsert(
            AuthorProfile(
                name = name,
                bio = bio,
                source = AuthorProfile.SOURCE_AI,
                updateTime = System.currentTimeMillis(),
                model = model,
            )
        )
    }

    /**
     * 一次性迁移：旧版把全部简介塞在 AppConfigStore 的单个 JSON 里，
     * 而 settings DataStore 不能放大 value（会阻塞 App.onCreate 的同步预载），故搬到 Room。
     * 迁移后清掉旧 key，之后每次启动只是一次内存读取，无副作用。
     */
    suspend fun migrateLegacyBios() {
        val json = AppConfigStore.getString(LEGACY_KEY)
        if (json.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        val profiles = parseLegacyBios(json).mapNotNull { (name, bio) ->
            if (name.isBlank() || bio.isBlank()) {
                null
            } else {
                AuthorProfile(
                    name = name,
                    bio = bio,
                    source = AuthorProfile.SOURCE_MANUAL,
                    updateTime = now,
                )
            }
        }
        if (profiles.isNotEmpty()) {
            dao.insertAllIfAbsent(profiles)
        }
        AppConfigStore.remove(LEGACY_KEY)
    }

    private fun parseLegacyBios(json: String): Map<String, String> = runCatching {
        val obj = JSONObject(json)
        val map = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = obj.optString(key, "")
        }
        map
    }.getOrDefault(emptyMap())

    private companion object {
        const val LEGACY_KEY = "author_intros"
    }
}
