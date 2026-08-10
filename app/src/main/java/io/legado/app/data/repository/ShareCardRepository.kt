package io.legado.app.data.repository

import io.legado.app.data.dao.ShareCardTemplateDao
import io.legado.app.data.entities.ShareCardTemplate
import io.legado.app.help.config.AppConfigStore
import kotlinx.coroutines.flow.Flow

class ShareCardRepository(
    private val dao: ShareCardTemplateDao,
) {

    companion object {
        private const val PREF_SELECTED_TEMPLATE_ID = "selectedShareCardTemplateId"
    }

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
