package io.legado.app.ui.book.tag

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity

class BookTagManageActivity : BaseComposeActivity() {

    private val viewModel by viewModels<BookTagManageViewModel>()

    @Composable
    override fun Content() {
        BookTagManageScreen(
            viewModel = viewModel,
            onBack = { finish() }
        )
    }
}
