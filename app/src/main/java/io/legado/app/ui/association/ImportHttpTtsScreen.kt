package io.legado.app.ui.association

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.data.entities.HttpTTS
import io.legado.app.ui.widget.components.importComponents.BaseImportUiState
import io.legado.app.ui.widget.components.importComponents.BatchImportDialog

@Composable
fun ImportHttpTtsScreen(
    state: BaseImportUiState<HttpTTS>,
    onIntent: (ImportHttpTtsIntent) -> Unit,
) {
    BatchImportDialog(
        title = stringResource(R.string.import_tts),
        importState = state,
        onDismissRequest = { onIntent(ImportHttpTtsIntent.Dismiss) },
        onConfirm = { items -> onIntent(ImportHttpTtsIntent.Import(items)) },
        onToggleItem = { onIntent(ImportHttpTtsIntent.ToggleItem(it)) },
        onToggleAll = { onIntent(ImportHttpTtsIntent.ToggleAll(it)) },
        onUpdateItem = { index, tts -> onIntent(ImportHttpTtsIntent.UpdateItem(index, tts)) },
        itemTitle = { it.name },
        itemSubtitle = { it.url }
    )
}
