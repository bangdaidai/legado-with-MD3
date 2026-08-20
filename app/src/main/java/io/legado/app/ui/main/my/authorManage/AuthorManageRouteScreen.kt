package io.legado.app.ui.main.my.authorManage

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AuthorManageRouteScreen(
    onBack: () -> Unit,
    onClickAuthor: (String) -> Unit,
) {
    val viewModel = koinViewModel<AuthorManageViewModel>()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    AuthorManageScreen(
        uiState = uiState,
        onIntent = viewModel::onIntent,
        onBack = onBack,
        onClickAuthor = onClickAuthor,
    )
}
