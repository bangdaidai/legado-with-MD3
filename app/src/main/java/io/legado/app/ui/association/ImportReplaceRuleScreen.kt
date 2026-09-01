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
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.widget.components.importComponents.BaseImportUiState
import io.legado.app.ui.widget.components.importComponents.BatchImportDialog
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImportReplaceRuleScreen(
    state: BaseImportUiState<ReplaceRule>,
    onIntent: (ImportReplaceRuleIntent) -> Unit,
) {
    var showImportOptions by remember { mutableStateOf(false) }
    var showCustomGroup by remember { mutableStateOf(false) }
    val importSuccess = state as? BaseImportUiState.Success

    BatchImportDialog(
        title = stringResource(R.string.import_replace_rule),
        importState = state,
        onDismissRequest = { onIntent(ImportReplaceRuleIntent.Dismiss) },
        onConfirm = { items -> onIntent(ImportReplaceRuleIntent.Import(items)) },
        onToggleItem = { onIntent(ImportReplaceRuleIntent.ToggleItem(it)) },
        onToggleAll = { onIntent(ImportReplaceRuleIntent.ToggleAll(it)) },
        onUpdateItem = { index, rule -> onIntent(ImportReplaceRuleIntent.UpdateItem(index, rule)) },
        topBarActions = {
            Box {
                MediumTonalButton(
                    icon = AppIcons.MoreVert,
                    contentDescription = stringResource(R.string.menu),
                    onClick = { showImportOptions = true },
                )
                RoundDropdownMenu(showImportOptions, { showImportOptions = false }) { dismiss ->
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.diy_source_group),
                        onClick = { dismiss(); showCustomGroup = true }
                    )
                }
            }
        },
        itemTitle = { it.name },
        itemSubtitle = { rule ->
            if (rule.group.isNullOrBlank()) rule.name
            else "${rule.name}(${rule.group})"
        }
    )

    ImportReplaceRuleCustomGroupDialog(
        show = showCustomGroup,
        initialGroup = importSuccess?.customGroup.orEmpty(),
        initialAdd = importSuccess?.isAddGroup == true,
        onDismissRequest = { showCustomGroup = false },
        onConfirm = { group, add ->
            showCustomGroup = false
            onIntent(ImportReplaceRuleIntent.SetCustomGroup(group, add))
        },
    )
}

@Composable
private fun ImportReplaceRuleCustomGroupDialog(
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
                io.legado.app.ui.widget.components.settingItem.SwitchSettingItem(
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
