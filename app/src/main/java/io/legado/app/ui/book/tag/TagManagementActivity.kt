package io.legado.app.ui.book.tag

import androidx.compose.runtime.Composable
import io.legado.app.ui.common.BaseComposeActivity

class TagManagementActivity : BaseComposeActivity() {
    @Composable
    override fun Content() {
        TagManagementRoute(onBack = { finishAfterTransition() })
    }
}
