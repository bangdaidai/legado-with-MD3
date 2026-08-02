package io.legado.app.data.repository

import io.legado.app.data.dao.BookplateTemplateDao
import io.legado.app.data.entities.BookplateTemplate
import io.legado.app.help.config.AppConfigStore
import kotlinx.coroutines.flow.Flow

class BookplateRepository(
    private val dao: BookplateTemplateDao,
) {

    companion object {
        private const val PREF_SELECTED_TEMPLATE_ID = "selectedBookplateTemplateId"
    }

    // region 偏好：选中的模板 ID

    fun getSelectedTemplateId(): Long =
        AppConfigStore.getLong(PREF_SELECTED_TEMPLATE_ID) ?: 0L

    fun setSelectedTemplateId(id: Long) {
        AppConfigStore.putLong(PREF_SELECTED_TEMPLATE_ID, id)
    }

    // endregion

    // region DAO 转发

    fun flowAll(): Flow<List<BookplateTemplate>> = dao.flowAll()

    suspend fun getAll(): List<BookplateTemplate> = dao.getAll()

    suspend fun getById(id: Long): BookplateTemplate? = dao.getById(id)

    suspend fun getDistinctGroupNames(): List<String> = dao.getDistinctGroupNames()

    suspend fun getByGroupName(groupName: String): List<BookplateTemplate> =
        dao.getByGroupName(groupName)

    suspend fun getBuiltinsByGroupName(groupName: String): List<BookplateTemplate> =
        dao.getBuiltinsByGroupName(groupName)

    suspend fun insert(template: BookplateTemplate): Long = dao.insert(template)

    suspend fun update(template: BookplateTemplate) = dao.update(template)

    suspend fun delete(template: BookplateTemplate) = dao.delete(template)

    // endregion
}
