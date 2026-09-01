package io.legado.app.ui.association

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.ui.widget.components.importComponents.BaseImportUiState
import io.legado.app.ui.widget.components.importComponents.BatchImportDialog

@Composable
fun ImportTxtTocRuleScreen(
    state: BaseImportUiState<TxtTocRule>,
    onIntent: (ImportTxtTocRuleIntent) -> Unit,
) {
    BatchImportDialog(
        title = stringResource(R.string.import_txt_toc_rule),
        importState = state,
        onDismissRequest = { onIntent(ImportTxtTocRuleIntent.Dismiss) },
        onConfirm = { items -> onIntent(ImportTxtTocRuleIntent.Import(items)) },
        onToggleItem = { onIntent(ImportTxtTocRuleIntent.ToggleItem(it)) },
        onToggleAll = { onIntent(ImportTxtTocRuleIntent.ToggleAll(it)) },
        onUpdateItem = { index, rule -> onIntent(ImportTxtTocRuleIntent.UpdateItem(index, rule)) },
        itemTitle = { it.name },
        itemSubtitle = { it.chapterRule }
    )
}
