package io.legado.app.ui.book.bookplate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.GroupManageBottomSheet
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.card.SelectionItemCard
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.tabRow.AppTabRow
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * 藏书票模板管理页。
 *
 * 参考替换净化页面风格：顶部 TabRow 分组、卡片列表、分组管理弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookplateManageScreen(
    state: BookplateManageUiState,
    onIntent: (BookplateManageIntent) -> Unit,
    effects: Flow<BookplateManageEffect>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    LaunchedEffect(Unit) {
        effects.collectLatest { effect ->
            when (effect) {
                is BookplateManageEffect.ShowToast -> context.toastOnUi(effect.message)
            }
        }
    }

    // 分组管理弹窗
    GroupManageBottomSheet(
        show = state.showGroupManage,
        groups = state.groups,
        onDismissRequest = { onIntent(BookplateManageIntent.DismissGroupManage) },
        onUpdateGroup = { old, new -> onIntent(BookplateManageIntent.RenameGroup(old, new)) },
        onDeleteGroup = { onIntent(BookplateManageIntent.DeleteGroup(it)) }
    )

    // 删除确认对话框
    AppAlertDialog(
        show = state.deleteConfirm != null,
        onDismissRequest = { onIntent(BookplateManageIntent.DismissDelete) },
        title = "删除模板",
        text = "确定要删除模板「${state.deleteConfirm?.name ?: ""}」吗？",
        confirmText = "删除",
        onConfirm = { onIntent(BookplateManageIntent.ConfirmDelete) },
        dismissText = "取消",
        onDismiss = { onIntent(BookplateManageIntent.DismissDelete) }
    )

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "藏书票模板",
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                actions = {
                    TopBarActionButton(
                        onClick = { onIntent(BookplateManageIntent.StartEdit(null)) },
                        imageVector = Icons.Default.Add,
                        contentDescription = "新建",
                    )
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        TopBarActionButton(
                            onClick = { showMenu = true },
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "更多",
                        )
                        RoundDropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            RoundDropdownMenuItem(
                                text = "分组管理",
                                onClick = {
                                    onIntent(BookplateManageIntent.ShowGroupManage)
                                    showMenu = false
                                }
                            )
                            RoundDropdownMenuItem(
                                text = "恢复内置模板",
                                onClick = {
                                    onIntent(BookplateManageIntent.RestoreBuiltins)
                                    showMenu = false
                                }
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // Tab 分组切换（参考替换净化）
            val tabItems = remember(state.groups) { listOf("全部") + state.groups }
            val selectedTabIndex = state.selectedGroup
                ?.let(tabItems::indexOf)
                ?.takeIf { it >= 0 }
                ?: 0

            if (tabItems.size > 1) {
                AppTabRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    tabTitles = tabItems,
                    selectedTabIndex = selectedTabIndex,
                    onTabSelected = { index ->
                        val group = if (index == 0) null else tabItems[index]
                        onIntent(BookplateManageIntent.SelectGroup(group))
                    }
                )
            }

            // 模板列表（参考替换净化卡片风格）
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.templates, key = { it.id }) { template ->
                    SelectionItemCard(
                        title = template.name.ifBlank { "未命名" },
                        subtitle = buildString {
                            if (template.groupName.isNotBlank()) append(template.groupName)
                            if (template.isBuiltin) {
                                if (isNotEmpty()) append(" · ")
                                append("内置")
                            }
                        }.ifBlank { null },
                        isSelected = template.id == state.selectedTemplateId,
                        onToggleSelection = {
                            onIntent(BookplateManageIntent.SelectTemplate(template.id))
                        },
                        leadingContent = {
                            RadioButton(
                                selected = template.id == state.selectedTemplateId,
                                onClick = {
                                    onIntent(BookplateManageIntent.SelectTemplate(template.id))
                                },
                            )
                        },
                        onClickEdit = {
                            onIntent(BookplateManageIntent.StartEdit(template))
                        },
                        trailingAction = {
                            SmallPlainButton(
                                onClick = {
                                    onIntent(BookplateManageIntent.RequestDelete(template))
                                },
                                icon = AppIcons.Delete,
                                contentDescription = "删除",
                            )
                        }
                    )
                }
            }
        }
    }

    // 编辑模板 Sheet
    state.editing?.let { editing ->
        BookplateEditSheet(
            editing = editing,
            groups = state.groups,
            onIntent = onIntent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookplateEditSheet(
    editing: io.legado.app.data.entities.BookplateTemplate,
    groups: List<String>,
    onIntent: (BookplateManageIntent) -> Unit,
) {
    var name by remember(editing.id) { mutableStateOf(editing.name) }
    var html by remember(editing.id) { mutableStateOf(editing.htmlContent) }
    var group by remember(editing.id) { mutableStateOf(editing.groupName) }

    AppModalBottomSheet(
        show = true,
        onDismissRequest = { onIntent(BookplateManageIntent.CancelEdit) },
        title = if (editing.id == 0L) "新建模板" else "编辑模板",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = "模板名称",
                singleLine = true,
            )

            // 分组选择（参考添加标签对话框的 GroupField）
            GroupSelector(
                groups = groups,
                selectedGroup = group,
                onSelect = { group = it },
            )

            AppTextField(
                value = html,
                onValueChange = { html = it },
                modifier = Modifier.fillMaxWidth(),
                label = "HTML 内容",
                minLines = 4,
                maxLines = 8,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (editing.id != 0L) {
                    MediumTonalButton(
                        onClick = { onIntent(BookplateManageIntent.RequestDelete(editing)) },
                        modifier = Modifier.weight(1f),
                        text = "删除",
                    )
                }
                MediumTonalButton(
                    onClick = { onIntent(BookplateManageIntent.SaveTemplate(name, html, group)) },
                    modifier = Modifier.weight(1f),
                    text = "保存",
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupSelector(
    groups: List<String>,
    selectedGroup: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        AppTextField(
            value = selectedGroup.ifBlank { "未分组" },
            onValueChange = {},
            readOnly = true,
            label = "分组",
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true),
        )
        RoundDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            RoundDropdownMenuItem(
                text = "未分组",
                onClick = { onSelect(""); expanded = false },
            )
            groups.forEach { g ->
                RoundDropdownMenuItem(
                    text = g,
                    onClick = { onSelect(g); expanded = false },
                )
            }
        }
    }
}
