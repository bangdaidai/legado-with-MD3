package io.legado.app.ui.book.source.debug

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.collectLatest

@Composable
fun BookSourceDebugRoute(
    sourceUrl: String?,
    viewModel: BookSourceDebugViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    LaunchedEffect(sourceUrl, viewModel) {
        viewModel.onIntent(BookSourceDebugIntent.Load(sourceUrl))
        viewModel.effects.collectLatest { effect ->
            if (effect is BookSourceDebugEffect.ShowMessage) context.toastOnUi(effect.message)
        }
    }
    // 不能在 ON_STOP 时自动停止：人机验证页（如起点 WAF）会把本页盖住，
    // 一旦 Stop 就会话被取消、Debug.debugSource 置空，验证返回之后的日志全部被丢弃，
    // 表现为「获取书籍列表之后再无输出」。会话的真正回收交给 ViewModel.onCleared。
    BookSourceDebugScreen(state, viewModel::onIntent, onBack)
}
