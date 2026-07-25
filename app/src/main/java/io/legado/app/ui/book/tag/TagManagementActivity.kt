package io.legado.app.ui.book.tag

import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity

class TagManagementActivity : BaseComposeActivity() {
    @Composable
    override fun Content() {
        TagManagementRoute(onBack = { finishAfterTransition() })
    }
}
