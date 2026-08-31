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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.livedata.observeAsState
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.RssSource
import io.legado.app.domain.gateway.OtherSettingsGateway
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.importComponents.BatchImportDialog
import io.legado.app.ui.widget.components.importComponents.ImportStatus
import io.legado.app.ui.widget.components.importComponents.associationImportState
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.settingItem.SwitchSettingItem
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * 导入 rss 源弹出窗口（Compose 版，沿用 BatchImportDialog 规范）。
 */
class ImportRssSourceDialog() : BaseComposeDialogFragment() {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    private val viewModel by viewModel<ImportRssSourceViewModel>()
    private val otherSettingsGateway by inject<OtherSettingsGateway>()
    private var finishOnDismiss = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finishOnDismiss = arguments?.getBoolean("finishOnDismiss") == true
        val source = arguments?.getString("source")
        if (source.isNullOrEmpty()) {
            dismissAllowingStateLoss()
        } else {
            viewModel.importSource(source)
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
        val scope = rememberCoroutineScope()
        val error by viewModel.errorLiveData.observeAsState()
        val successCount by viewModel.successLiveData.observeAsState(-1)
        var refresh by remember { mutableIntStateOf(0) }
        var keepVersion by remember { mutableIntStateOf(0) }
        var showGroupDialog by remember { mutableStateOf(false) }

        val settings = remember(keepVersion) { otherSettingsGateway.currentSettings }
        val importState = remember(refresh, error, successCount, keepVersion) {
            associationImportState(
                error = error,
                loaded = successCount >= 0,
                items = viewModel.allSources,
                existing = viewModel.checkSources,
                selected = viewModel.selectStatus,
                statusOf = { existing, incoming ->
                    when {
                        existing == null -> ImportStatus.New
                        incoming.lastUpdateTime > existing.lastUpdateTime -> ImportStatus.Update
                        else -> ImportStatus.Existing
                    }
                }
            )
        }

        BatchImportDialog(
            asDialog = true,
            title = stringResource(R.string.import_rss_source),
            importState = importState,
            onDismissRequest = { dismissAllowingStateLoss() },
            onConfirm = { viewModel.importSelect { dismissAllowingStateLoss() } },
            onToggleItem = { index ->
                refresh++
                viewModel.selectStatus[index] = !(viewModel.selectStatus[index])
            },
            onToggleAll = { selectAll ->
                refresh++
                viewModel.selectStatus.forEachIndexed { index, _ ->
                    viewModel.selectStatus[index] = selectAll
                }
            },
            onUpdateItem = { index, data ->
                refresh++
                (data as? RssSource)?.let { viewModel.allSources[index] = it }
            },
            topBarActions = {
                var menuExpanded by remember { mutableStateOf(false) }
                RoundDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) { dismiss ->
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.keep_original_name),
                        isSelected = settings.importKeepName,
                        onClick = {
                            dismiss()
                            scope.launch {
                                otherSettingsGateway.update { it.copy(importKeepName = !settings.importKeepName) }
                            }
                            keepVersion++
                        }
                    )
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.keep_group),
                        isSelected = settings.importKeepGroup,
                        onClick = {
                            dismiss()
                            scope.launch {
                                otherSettingsGateway.update { it.copy(importKeepGroup = !settings.importKeepGroup) }
                            }
                            keepVersion++
                        }
                    )
                    RoundDropdownMenuItem(
                        text = stringResource(R.string.keep_enable),
                        isSelected = settings.importKeepEnable,
                        onClick = {
                            dismiss()
                            scope.launch {
                                otherSettingsGateway.update { it.copy(importKeepEnable = !settings.importKeepEnable) }
                            }
                            keepVersion++
                        }
                    )
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
            itemTitle = { it.sourceName },
            itemSubtitle = { it.sourceUrl }
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
