package io.legado.app.ui.book.tagdetail

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TagDetailRouteScreen(
    tagId: Long,
    onBack: () -> Unit,
) {
    val viewModel = koinViewModel<TagDetailViewModel>(parameters = { parametersOf(tagId) })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.effect.collectLatest {
            when (it) {
                TagDetailEffect.Back -> onBack()
                is TagDetailEffect.ShowMessage ->
                    Toast.makeText(context, it.msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    TagDetailScreen(
        state = state,
        onIntent = viewModel::sendEvent,
        onBack = onBack,
    )
}
