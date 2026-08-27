package io.legado.app.data.repository

import io.legado.app.data.dao.ShareCardTemplateDao
import io.legado.app.data.entities.ShareCardTemplate
import io.legado.app.help.config.AppConfigStore
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.flow.Flow

class ShareCardRepository(
    private val dao: ShareCardTemplateDao,
) {

    companion object {
        private const val PREF_SELECTED_TEMPLATE_ID = "selectedShareCardTemplateId"
        private const val PREF_SCENE_GROUP_MAP = "shareCardSceneGroupMap"
    }

    // region 场景 ↔ 分组 绑定（以分组为中心存储：分组名 -> 绑定的场景 key 列表）

    /** 分组名 -> 该分组绑定的场景 key 列表 */
    fun getSceneGroupMap(): Map<String, List<String>> {
        val json = AppConfigStore.getString(PREF_SCENE_GROUP_MAP) ?: return emptyMap()
        return GSON.fromJsonObject<Map<String, List<String>>>(json).getOrNull() ?: emptyMap()
    }

    fun setSceneGroupMap(map: Map<String, List<String>>) {
        AppConfigStore.putString(PREF_SCENE_GROUP_MAP, GSON.toJson(map))
    }

    fun getScenesForGroup(group: String): List<String> = getSceneGroupMap()[group] ?: emptyList()

    fun setScenesForGroup(group: String, scenes: List<String>) {
        val map = getSceneGroupMap().toMutableMap()
        if (scenes.isEmpty()) map.remove(group) else map[group] = scenes
        setSceneGroupMap(map)
    }

    fun toggleSceneForGroup(group: String, sceneKey: String) {
        val current = getScenesForGroup(group).toMutableList()
        if (current.contains(sceneKey)) current.remove(sceneKey) else current.add(sceneKey)
        setScenesForGroup(group, current)
    }

    /** 场景 key -> 所有绑定了该场景的分组名（并集过滤用） */
    fun getGroupsForScene(sceneKey: String): List<String> =
        getSceneGroupMap().filter { it.value.contains(sceneKey) }.keys.toList()

    fun renameSceneGroupKey(oldName: String, newName: String) {
        val map = getSceneGroupMap().toMutableMap()
        val value = map.remove(oldName) ?: return
        map[newName] = value
        setSceneGroupMap(map)
    }

    fun removeSceneGroupKey(group: String) {
        val map = getSceneGroupMap().toMutableMap()
        if (map.remove(group) != null) setSceneGroupMap(map)
    }

    // endregion

    // region 偏好：选中的模板 ID（按场景记忆上次使用的模板）

    fun getSelectedTemplateId(sceneKey: String? = null): Long {
        val key = if (sceneKey != null) "${PREF_SELECTED_TEMPLATE_ID}_$sceneKey" else PREF_SELECTED_TEMPLATE_ID
        return AppConfigStore.getLong(key) ?: 0L
    }

    fun setSelectedTemplateId(sceneKey: String? = null, id: Long) {
        val key = if (sceneKey != null) "${PREF_SELECTED_TEMPLATE_ID}_$sceneKey" else PREF_SELECTED_TEMPLATE_ID
        AppConfigStore.putLong(key, id)
    }

    // endregion

    // region 偏好：每个分组的默认模板（分组名 -> 默认模板 ID；空分组名 "" 表示未分组/全局默认）

    private const val PREF_GROUP_DEFAULT_MAP = "shareCardGroupDefaultMap"

    /** 分组名 -> 该分组的默认模板 ID。 */
    fun getGroupDefaultMap(): Map<String, Long> {
        val json = AppConfigStore.getString(PREF_GROUP_DEFAULT_MAP) ?: return emptyMap()
        return GSON.fromJsonObject<Map<String, Long>>(json).getOrNull() ?: emptyMap()
    }

    private fun setGroupDefaultMap(map: Map<String, Long>) {
        AppConfigStore.putString(PREF_GROUP_DEFAULT_MAP, GSON.toJson(map))
    }

    /** 取某分组的默认模板 ID；空分组未设置时回退到旧版全局默认。 */
    fun getSelectedTemplateIdForGroup(group: String): Long {
        val map = getGroupDefaultMap()
        map[group]?.let { return it }
        if (group == "") return AppConfigStore.getLong(PREF_SELECTED_TEMPLATE_ID) ?: 0L
        return 0L
    }

    fun setSelectedTemplateIdForGroup(group: String, id: Long) {
        val map = getGroupDefaultMap().toMutableMap()
        map[group] = id
        setGroupDefaultMap(map)
        // 空分组（未分组）默认同时写旧版全局键，保证无分组场景的回退一致
        if (group == "") AppConfigStore.putLong(PREF_SELECTED_TEMPLATE_ID, id)
    }

    fun renameGroupDefaultKey(oldName: String, newName: String) {
        val map = getGroupDefaultMap().toMutableMap()
        val value = map.remove(oldName) ?: return
        map[newName] = value
        setGroupDefaultMap(map)
    }

    fun removeGroupDefaultKey(group: String) {
        val map = getGroupDefaultMap().toMutableMap()
        if (map.remove(group) != null) setGroupDefaultMap(map)
    }

    /** 场景绑定的分组里取第一个有默认的分组默认；未绑定分组则取空分组（全局）默认。 */
    fun getFirstGroupDefaultForScene(sceneKey: String): Long {
        val map = getGroupDefaultMap()
        for (group in getGroupsForScene(sceneKey)) {
            map[group]?.let { return it }
        }
        return map[""] ?: (AppConfigStore.getLong(PREF_SELECTED_TEMPLATE_ID) ?: 0L)
    }

    // endregion

    // region DAO 转发

    fun flowAll(): Flow<List<ShareCardTemplate>> = dao.flowAll()

    suspend fun getAll(): List<ShareCardTemplate> = dao.getAll()

    suspend fun getById(id: Long): ShareCardTemplate? = dao.getById(id)

    suspend fun getDistinctGroupNames(): List<String> = dao.getDistinctGroupNames()

    suspend fun getByGroupName(groupName: String): List<ShareCardTemplate> =
        dao.getByGroupName(groupName)

    suspend fun getBuiltinsByGroupName(groupName: String): List<ShareCardTemplate> =
        dao.getBuiltinsByGroupName(groupName)

    suspend fun insert(template: ShareCardTemplate): Long = dao.insert(template)

    suspend fun update(template: ShareCardTemplate) = dao.update(template)

    suspend fun delete(template: ShareCardTemplate) = dao.delete(template)

    suspend fun updateGroupName(oldName: String, newName: String) =
        dao.updateGroupName(oldName, newName)

    suspend fun deleteByGroupName(groupName: String) = dao.deleteByGroupName(groupName)

    // endregion
}
