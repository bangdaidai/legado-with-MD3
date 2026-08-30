package io.legado.app.ui.association

import android.content.DialogInterface
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.observeAsState
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.importComponents.ImportAssociationContent
import io.legado.app.ui.widget.components.importComponents.ImportStatus
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.settingItem.SwitchSettingItem
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * 导入替换规则（Compose 版，沿用 BatchImportDialog 规范）。
 */
class ImportReplaceRuleDialog() : BaseComposeDialogFragment() {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    private val viewModel by viewModel<ImportReplaceRuleViewModel>()
    private var finishOnDismiss = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finishOnDismiss = arguments?.getBoolean("finishOnDismiss") == true
        val source = arguments?.getString("source")
        if (source.isNullOrEmpty()) {
            dismissAllowingStateLoss()
        } else {
            viewModel.import(source)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (finishOnDismiss) {
            activity?.finish()
        }
    }

    @Composable
    override fun Content() {
        var showGroupDialog by remember { mutableStateOf(false) }

        ImportAssociationContent(
            title = stringResource(R.string.import_replace_rule),
            items = viewModel.allRules,
            existing = viewModel.checkRules,
            selected = viewModel.selectStatus,
            errorLiveData = viewModel.errorLiveData,
            successLiveData = viewModel.successLiveData,
            onImportSelect = { finally -> viewModel.importSelect(finally) },
            statusOf = { existing, incoming ->
                when {
                    existing == null -> ImportStatus.New
                    existing.pattern != incoming.pattern
                        || existing.replacement != incoming.replacement
                        || existing.isRegex != incoming.isRegex
                        || existing.scope != incoming.scope -> ImportStatus.Update
                    else -> ImportStatus.Existing
                }
            },
            itemTitle = { r -> if (r.group.isNullOrBlank()) r.name else "${r.name}(${r.group})" },
            onUpdateItem = { index, data ->
                (data as? ReplaceRule)?.let { viewModel.allRules[index] = it }
            },
            topBarActions = {
                var menuExpanded by remember { mutableStateOf(false) }
                RoundDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) { dismiss ->
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.diy_source_group),
                        onClick = {
                            dismiss()
                            showGroupDialog = true
                        }
                    )
                }
                MediumTonalButton(
                    modifier = Modifier.heightIn(min = 24.dp),
                    icon = AppIcons.MoreVert,
                    contentDescription = stringResource(R.string.menu),
                    onClick = { menuExpanded = true }
                )
            },
            onDismissRequest = { dismissAllowingStateLoss() }
        )

        if (showGroupDialog) {
            CustomGroupDialog(
                initialName = viewModel.groupName ?: "",
                initialAddGroup = viewModel.isAddGroup,
                onDismissRequest = { showGroupDialog = false },
                onConfirm = { name, addGroup ->
                    viewModel.groupName = name
                    viewModel.isAddGroup = addGroup
                    showGroupDialog = false
                }
            )
        }
    }

    @Composable
    private fun CustomGroupDialog(
        initialName: String,
        initialAddGroup: Boolean,
        onDismissRequest: () -> Unit,
        onConfirm: (String, Boolean) -> Unit
    ) {
        var name by remember(initialName) { mutableStateOf(initialName) }
        var addGroup by remember(initialAddGroup) { mutableStateOf(initialAddGroup) }
        AppAlertDialog(
            show = true,
            onDismissRequest = onDismissRequest,
            title = stringResource(R.string.diy_edit_source_group),
            confirmText = stringResource(android.R.string.ok),
            onConfirm = { onConfirm(name.trim(), addGroup) },
            dismissText = stringResource(android.R.string.cancel),
            onDismiss = onDismissRequest,
            content = {
                Column {
                    AppTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.group_name),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                    SwitchSettingItem(
                        title = stringResource(R.string.add_to_group),
                        checked = addGroup,
                        onCheckedChange = { addGroup = it }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        )
    }
}
