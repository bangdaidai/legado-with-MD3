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

    // region 偏好：选中的模板 ID（支持按场景区分）

    fun getSelectedTemplateId(sceneKey: String? = null): Long {
        val key = if (sceneKey != null) "${PREF_SELECTED_TEMPLATE_ID}_$sceneKey" else PREF_SELECTED_TEMPLATE_ID
        return AppConfigStore.getLong(key) ?: 0L
    }

    fun setSelectedTemplateId(sceneKey: String? = null, id: Long) {
        val key = if (sceneKey != null) "${PREF_SELECTED_TEMPLATE_ID}_$sceneKey" else PREF_SELECTED_TEMPLATE_ID
        AppConfigStore.putLong(key, id)
    }

    // endregion

    // region 偏好：选中的模板 ID

    fun getSelectedTemplateId(): Long =
        AppConfigStore.getLong(PREF_SELECTED_TEMPLATE_ID) ?: 0L

    fun setSelectedTemplateId(id: Long) {
        AppConfigStore.putLong(PREF_SELECTED_TEMPLATE_ID, id)
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
