package io.legado.app.ui.association

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.widget.components.importComponents.BaseImportUiState
import io.legado.app.ui.widget.components.importComponents.BatchImportDialog

@Composable
fun ImportDictRuleScreen(
    state: BaseImportUiState<DictRule>,
    onIntent: (ImportDictRuleIntent) -> Unit,
) {
    BatchImportDialog(
        title = stringResource(R.string.import_dict_rule),
        importState = state,
        onDismissRequest = { onIntent(ImportDictRuleIntent.Dismiss) },
        onConfirm = { items -> onIntent(ImportDictRuleIntent.Import(items)) },
        onToggleItem = { onIntent(ImportDictRuleIntent.ToggleItem(it)) },
        onToggleAll = { onIntent(ImportDictRuleIntent.ToggleAll(it)) },
        onUpdateItem = { index, rule -> onIntent(ImportDictRuleIntent.UpdateItem(index, rule)) },
        itemTitle = { it.name },
        itemSubtitle = { it.urlRule }
    )
}
