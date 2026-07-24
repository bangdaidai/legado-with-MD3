package io.legado.app.ui.book.readingmemory

import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity

class ReadingMemoryDetailActivity : BaseComposeActivity() {

    private val bookUrl: String? = intent.getStringExtra("bookUrl")

    @Composable
    override fun Content() {
        ReadingMemoryDetailRoute(
            bookUrl = bookUrl ?: "",
            onBack = { finishAfterTransition() },
        )
    }
}
