package io.legado.app.ui.main.my.authorManage

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AuthorManageRouteScreen(
    onBack: () -> Unit,
    onOpenBook: (bookUrl: String) -> Unit,
) {
    val viewModel = koinViewModel<AuthorManageViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                is AuthorManageEffect.ShowToast ->
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    AuthorManageScreen(
        uiState = uiState,
        onIntent = viewModel::onIntent,
        onBack = onBack,
        onOpenBook = onOpenBook,
    )
}
