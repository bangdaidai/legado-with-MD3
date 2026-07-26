package io.legado.app.ui.book.tagmanage

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import org.koin.androidx.compose.koinViewModel

@Composable
fun TagManagementRouteScreen(
    onBack: () -> Unit,
    onOpenTagDetail: (Long) -> Unit,
) {
    val viewModel = koinViewModel<TagManagementViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.effect.collectLatest {
            when (it) {
                is TagManagementEffect.NavigateToTagDetail -> onOpenTagDetail(it.tagId)
                is TagManagementEffect.ShowMessage ->
                    Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    TagManagementScreen(
        state = state,
        onIntent = viewModel::sendEvent,
        onBack = onBack,
    )
}
