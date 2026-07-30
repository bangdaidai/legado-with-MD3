package io.legado.app.ui.book.source.manage

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.base.BaseComposeActivity
import io.legado.app.data.appDb
import io.legado.app.ui.widget.components.GroupManageBottomSheet

/**
 * 书源分组管理，复用替换净化通用的 GroupManageBottomSheet（内联 SettingItem 编辑）。
 * 透明窗口，叠在书源管理列表之上。
 */
class BookSourceGroupManageActivity : BaseComposeActivity(transparent = true, imageBg = false) {

    private val viewModel by viewModels<BookSourceViewModel>()

    @Composable
    override fun Content() {
        val groups by remember { appDb.bookSourceDao.flowGroups() }
            .collectAsStateWithLifecycle(initialValue = emptyList())

        GroupManageBottomSheet(
            show = true,
            groups = groups,
            onDismissRequest = { finish() },
            onUpdateGroup = { old, new -> viewModel.onIntent(BookSourceIntent.UpdateGroup(old, new)) },
            onDeleteGroup = { viewModel.onIntent(BookSourceIntent.DeleteGroup(it)) },
        )
    }
}
