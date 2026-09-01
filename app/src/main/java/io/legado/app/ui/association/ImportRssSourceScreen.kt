package io.legado.app.ui.association

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.RssSource
import io.legado.app.ui.widget.components.importComponents.BaseImportUiState
import io.legado.app.ui.widget.components.importComponents.BatchImportDialog
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.settingItem.SwitchSettingItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImportRssSourceScreen(
    state: BaseImportUiState<RssSource>,
    onIntent: (ImportRssSourceIntent) -> Unit,
) {
    var showImportOptions by remember { mutableStateOf(false) }
    var showCustomGroup by remember { mutableStateOf(false) }
    val importSuccess = state as? BaseImportUiState.Success

    BatchImportDialog(
        title = stringResource(R.string.import_rss_source),
        importState = state,
        onDismissRequest = { onIntent(ImportRssSourceIntent.Dismiss) },
        onConfirm = { items -> onIntent(ImportRssSourceIntent.Import(items)) },
        onToggleItem = { onIntent(ImportRssSourceIntent.ToggleItem(it)) },
        onToggleAll = { onIntent(ImportRssSourceIntent.ToggleAll(it)) },
        onUpdateItem = { index, source -> onIntent(ImportRssSourceIntent.UpdateItem(index, source)) },
        topBarActions = {
            Box {
                MediumTonalButton(
                    icon = AppIcons.MoreVert,
                    contentDescription = stringResource(R.string.menu),
                    onClick = { showImportOptions = true },
                )
                RoundDropdownMenu(showImportOptions, { showImportOptions = false }) { dismiss ->
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.keep_original_name),
                        isSelected = importSuccess?.keepOriginalName == true,
                        onClick = {
                            dismiss()
                            onIntent(
                                ImportRssSourceIntent.SetCustomGroup(
                                    importSuccess?.customGroup.orEmpty(),
                                    importSuccess?.isAddGroup == true
                                )
                            )
                        },
                    )
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.keep_group),
                        isSelected = importSuccess?.keepOriginalGroup == true,
                        onClick = { dismiss() },
                    )
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.keep_enable),
                        isSelected = importSuccess?.keepOriginalEnable == true,
                        onClick = { dismiss() },
                    )
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.diy_source_group),
                        onClick = { dismiss(); showCustomGroup = true }
                    )
                }
            }
        },
        itemTitle = { it.sourceName },
        itemSubtitle = { it.sourceUrl }
    )

    ImportRssSourceCustomGroupDialog(
        show = showCustomGroup,
        initialGroup = importSuccess?.customGroup.orEmpty(),
        initialAdd = importSuccess?.isAddGroup == true,
        onDismissRequest = { showCustomGroup = false },
        onConfirm = { group, add ->
            showCustomGroup = false
            onIntent(ImportRssSourceIntent.SetCustomGroup(group, add))
        },
    )
}

@Composable
private fun ImportRssSourceCustomGroupDialog(
    show: Boolean,
    initialGroup: String,
    initialAdd: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: (String, Boolean) -> Unit,
) {
    var group by remember(show, initialGroup) { mutableStateOf(initialGroup) }
    var add by remember(show, initialAdd) { mutableStateOf(initialAdd) }

    io.legado.app.ui.widget.components.alert.AppAlertDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.diy_source_group),
        content = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
            ) {
                io.legado.app.ui.widget.components.AppTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = stringResource(R.string.group_name),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                SwitchSettingItem(
                    title = stringResource(R.string.add_group),
                    checked = add,
                    onCheckedChange = { add = it },
                )
            }
        },
        confirmText = stringResource(R.string.ok),
        onConfirm = { onConfirm(group.trim(), add) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = onDismissRequest,
    )
}
