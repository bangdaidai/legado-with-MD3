package io.legado.app.ui.rss.source.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.collectLatest

@Composable
fun RssSourceDebugRoute(
    sourceUrl: String?,
    viewModel: RssSourceDebugViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    LaunchedEffect(sourceUrl, viewModel) {
        viewModel.onIntent(RssSourceDebugIntent.Load(sourceUrl))
        viewModel.effects.collectLatest { effect ->
            if (effect is RssSourceDebugEffect.ShowMessage) context.toastOnUi(effect.value)
        }
    }
    // 同书源调试页：验证页盖住本页时不能自动停止，否则会话被取消、后续日志全部丢弃。
    RssSourceDebugScreen(state, viewModel::onIntent, onBack)
}
