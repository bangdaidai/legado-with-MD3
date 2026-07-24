package io.legado.app.ui.book.tag

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity

/**
 * 标签详情页承载 Activity（F1）
 * 通过 intent extra "tagId" 指定要展示的标签。
 */
class BookTagDetailActivity : BaseComposeActivity() {

    private val viewModel by viewModels<BookTagDetailViewModel>()
    private val tagId: Long by lazy { intent.getLongExtra("tagId", 0L) }

    @Composable
    override fun Content() {
        BookTagDetailScreen(
            viewModel = viewModel,
            tagId = tagId,
            onBack = { finish() }
        )
    }
}
