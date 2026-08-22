package io.legado.app.ui.main.my.authorManage

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AuthorDetailRouteScreen(
    name: String,
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
) {
    val viewModel = koinViewModel<AuthorDetailViewModel>(parameters = { parametersOf(name) })
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is AuthorDetailEffect.ShowToast ->
                    Toast.makeText(context, effect.messageResId, Toast.LENGTH_SHORT).show()

                is AuthorDetailEffect.ShowError ->
                    Toast.makeText(context, effect.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    AuthorDetailScreen(
        uiState = uiState,
        onIntent = viewModel::onIntent,
        onBack = onBack,
        onOpenBook = onOpenBook,
    )
}
