package io.legado.app.ui.book.marking

import androidx.compose.runtime.Composable
import io.legado.app.base.BaseComposeActivity

/**
 * 所有笔记（划线/高亮）
 */
class AllMarkingActivity : BaseComposeActivity() {
    @Composable
    override fun Content() {
        AllMarkingRouteScreen(
            onBack = { finish() }
        )
    }
}
