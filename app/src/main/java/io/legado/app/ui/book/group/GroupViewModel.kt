package io.legado.app.ui.book.group

import android.app.Application
import androidx.lifecycle.viewModelScope
import io.legado.app.base.BaseViewModel
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.TagGroupRule
import io.legado.app.data.repository.BookGroupRepository
import io.legado.app.data.repository.TagGroupRuleRepository
import io.legado.app.domain.gateway.BookGroupMutationGateway
import io.legado.app.domain.gateway.PrivateAccessGateway
import io.legado.app.domain.model.BookGroupUpdate
import io.legado.app.domain.model.NewBookGroup
import io.legado.app.domain.model.TagGroupRuleUpdate
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupViewModel(
    application: Application,
    private val bookGroupRepository: BookGroupRepository,
    private val bookGroupMutationGateway: BookGroupMutationGateway,
    private val privateAccessGateway: PrivateAccessGateway,
) : BaseViewModel(application) {

    private val tagGroupRuleRepository = TagGroupRuleRepository()

    /** 是否设了本地密码：没设就无法校验，私密分组的关闭/删除退回普通确认框。Eagerly 保证开框即读到真值。 */
    val hasLocalPassword: StateFlow<Boolean> = privateAccessGateway.state
        .map { it.hasPassword }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 关闭/删除私密分组前的身份校验。走 verifyPasswordOnly：只证明是本人，
     * 不顺手把私密内容解锁（与"进隐私设置页"同一套语义）。
     */
    fun verifyPrivatePassword(input: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { privateAccessGateway.verifyPasswordOnly(input) }
                .getOrDefault(false)
            onResult(ok)
        }
    }

    fun upGroup(vararg bookGroup: BookGroup, finally: (() -> Unit)? = null) {
        execute {
            bookGroupRepository.update(*bookGroup)
        }.onFinally {
            finally?.invoke()
        }
    }

    fun addGroup(
        groupName: String,
        bookSort: Int,
        enableRefresh: Boolean,
        isPrivate: Boolean,
        cover: String?,
        pattern: String? = null,
        onError: ((Throwable) -> Unit)? = null,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            try {
                bookGroupMutationGateway.addGroup(
                    NewBookGroup(
                        groupName = groupName,
                        bookSort = bookSort,
                        enableRefresh = enableRefresh,
                        isPrivate = isPrivate,
                        cover = cover,
                        pattern = pattern,
                    )
                )
                onSuccess()
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                onError?.invoke(error)
            }
        }
    }

    fun saveGroup(
        bookGroup: BookGroup,
        ruleToSave: TagGroupRule?,
        ruleToDelete: TagGroupRule?,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        viewModelScope.launch {
            try {
                bookGroupMutationGateway.saveGroup(
                    bookGroup = bookGroup.toUpdate(),
                    ruleToSave = ruleToSave?.toUpdate(),
                    ruleIdToDelete = ruleToDelete?.id,
                )
                onSuccess()
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                onError(error)
            }
        }
    }

    fun delGroup(bookGroup: BookGroup, finally: () -> Unit) {
        execute {
            bookGroupMutationGateway.deleteGroup(bookGroup.groupId)
        }.onFinally {
            finally()
        }
    }

    fun clearCover(bookGroup: BookGroup, finally: () -> Unit) {
        execute {
            bookGroupRepository.clearCover(bookGroup.groupId)
        }.onFinally {
            finally()
        }
    }

    suspend fun getTagGroupRule(groupName: String): TagGroupRule? {
        return tagGroupRuleRepository.getByGroupName(groupName)
    }

    fun saveTagGroupRule(rule: TagGroupRule, finally: (() -> Unit)? = null) {
        execute {
            bookGroupMutationGateway.saveTagGroupRule(rule.toUpdate())
        }.onFinally {
            finally?.invoke()
        }
    }

    fun deleteTagGroupRule(rule: TagGroupRule, finally: (() -> Unit)? = null) {
        execute {
            bookGroupMutationGateway.deleteTagGroupRule(rule.id)
        }.onFinally {
            finally?.invoke()
        }
    }

    private fun BookGroup.toUpdate() = BookGroupUpdate(
        groupId = groupId,
        groupName = groupName,
        cover = cover,
        order = order,
        enableRefresh = enableRefresh,
        show = show,
        bookSort = bookSort,
        isPrivate = isPrivate,
    )

    private fun TagGroupRule.toUpdate() = TagGroupRuleUpdate(
        id = id,
        pattern = pattern,
        groupName = groupName,
        order = order,
    )

}
