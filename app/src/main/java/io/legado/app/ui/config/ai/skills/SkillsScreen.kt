package io.legado.app.ui.config.ai.skills

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.domain.model.AiTaskType
import io.legado.app.domain.model.aiTaskSceneLabel
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.SplicedColumnGroup
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.ConfirmDismissButtonsRow
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.ClickableSettingItem
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import org.koin.androidx.compose.koinViewModel
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.getClipText
import io.legado.app.utils.sendToClip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

private val createTaskTypes = listOf(
    AiTaskType.CHAT,
    AiTaskType.TRANSLATE_CHAPTER,
    AiTaskType.SUMMARIZE_CHAPTER,
    AiTaskType.SUMMARIZE_BOOK,
    AiTaskType.EXPLAIN_SELECTION,
    AiTaskType.CLEAN_SELECTION,
    AiTaskType.TEXT_FACTORY,
    AiTaskType.REWRITE_TEXT,
    AiTaskType.ANALYZE_SPEECH,
    AiTaskType.IDENTIFY_CHARACTERS,
    AiTaskType.BOOKSHELF_AUTO_GROUP,
    AiTaskType.AUTHOR_BIO,
    AiTaskType.TOC_RULE,
)

@Composable
fun SkillsRouteScreen(
    onBackClick: () -> Unit,
    viewModel: SkillsViewModel = koinViewModel(),
) {
    SkillsScreen(
        state = viewModel.uiState.collectAsStateWithLifecycle().value,
        effects = viewModel.effects,
        onIntent = viewModel::onIntent,
        onBackClick = onBackClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    state: SkillsUiState,
    effects: Flow<SkillsEffect>,
    onIntent: (SkillsIntent) -> Unit,
    onBackClick: () -> Unit
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var selectedTaskPreset by remember { mutableStateOf<AiSkillTaskPresetUi?>(null) }
    var selectedPromptPreset by remember { mutableStateOf<AiSkillPromptPresetUi?>(null) }
    var showBindModelSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        effects.collectLatest { effect ->
            when (effect) {
                is SkillsEffect.ShowMessage -> snackbarHostState.showSnackbar(effect.message)
                is SkillsEffect.CopyText -> context.sendToClip(effect.text)
            }
        }
    }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.ai_skills),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBackClick)
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            contentPadding = adaptiveContentPadding(
                top = paddingValues.calculateTopPadding(),
                bottom = 120.dp
            )
        ) {
            item {
                SplicedColumnGroup(title = stringResource(R.string.ai_skills)) {
                    ClickableSettingItem(
                        title = stringResource(R.string.ai_skill_import),
                        description = stringResource(R.string.ai_skill_import_desc),
                        onClick = {
                            onIntent(SkillsIntent.ParseImport(context.getClipText().orEmpty()))
                        }
                    )
                    ClickableSettingItem(
                        title = stringResource(R.string.ai_skill_create),
                        description = stringResource(R.string.ai_skill_create_desc),
                        onClick = { onIntent(SkillsIntent.StartCreate) }
                    )
                }
            }

            items(state.taskGroups, key = { it.taskType }) { group ->
                SplicedColumnGroup(title = group.taskLabel) {
                    group.presets.forEach { preset ->
                        ClickableSettingItem(
                            title = preset.name,
                            description = when {
                                !preset.modelBound -> stringResource(R.string.ai_skill_model_missing)
                                else -> preset.modelName
                            },
                            option = if (preset.isDefault) {
                                stringResource(R.string.ai_skill_default_tag)
                            } else {
                                null
                            },
                            onClick = {
                                selectedTaskPreset = preset
                                showBindModelSheet = false
                                showDeleteConfirm = false
                            }
                        )
                    }
                }
            }

            if (state.promptPresets.isNotEmpty()) {
                item {
                    SplicedColumnGroup(title = stringResource(R.string.ai_skill_group_prompts)) {
                        state.promptPresets.forEach { preset ->
                            ClickableSettingItem(
                                title = preset.name,
                                description = preset.instruction,
                                onClick = { selectedPromptPreset = preset }
                            )
                        }
                    }
                }
            }
        }
    }

    // 任务预设操作菜单
    AppModalBottomSheet(
        show = selectedTaskPreset != null && !showBindModelSheet && !showDeleteConfirm,
        onDismissRequest = { selectedTaskPreset = null },
        title = selectedTaskPreset?.name.orEmpty()
    ) {
        LazyColumn {
            item {
                ClickableSettingItem(
                    title = stringResource(R.string.ai_skill_export),
                    onClick = {
                        selectedTaskPreset?.let { onIntent(SkillsIntent.ExportTaskPreset(it.id)) }
                        selectedTaskPreset = null
                    }
                )
                if (selectedTaskPreset?.isDefault == false) {
                    ClickableSettingItem(
                        title = stringResource(R.string.ai_skill_set_default),
                        onClick = {
                            selectedTaskPreset?.let { onIntent(SkillsIntent.SetDefaultTaskPreset(it.id)) }
                            selectedTaskPreset = null
                        }
                    )
                }
                ClickableSettingItem(
                    title = stringResource(R.string.ai_skill_bind_model),
                    onClick = { showBindModelSheet = true }
                )
                if (selectedTaskPreset?.isDefault == false) {
                    ClickableSettingItem(
                        title = stringResource(R.string.ai_skill_delete),
                        onClick = { showDeleteConfirm = true }
                    )
                }
            }
        }
    }

    // 绑定模型选择
    AppModalBottomSheet(
        show = showBindModelSheet,
        onDismissRequest = {
            showBindModelSheet = false
            selectedTaskPreset = null
        },
        title = stringResource(R.string.ai_skill_select_model)
    ) {
        LazyColumn {
            items(state.models, key = { it.modelProfileId }) { model ->
                ClickableSettingItem(
                    title = model.label,
                    onClick = {
                        selectedTaskPreset?.let {
                            onIntent(SkillsIntent.BindTaskPresetModel(it.id, model.modelProfileId))
                        }
                        showBindModelSheet = false
                        selectedTaskPreset = null
                    }
                )
            }
        }
    }

    // 删除确认
    AppAlertDialog(
        show = showDeleteConfirm,
        onDismissRequest = {
            showDeleteConfirm = false
            selectedTaskPreset = null
        },
        title = stringResource(R.string.ai_skill_delete),
        text = stringResource(R.string.ai_skill_delete_confirm),
        confirmText = stringResource(R.string.ai_skill_delete),
        onConfirm = {
            selectedTaskPreset?.let { onIntent(SkillsIntent.DeleteTaskPreset(it.id)) }
            showDeleteConfirm = false
            selectedTaskPreset = null
        },
        dismissText = stringResource(R.string.cancel),
        onDismiss = {
            showDeleteConfirm = false
        }
    )

    // 指令预设操作菜单
    AppModalBottomSheet(
        show = selectedPromptPreset != null,
        onDismissRequest = { selectedPromptPreset = null },
        title = selectedPromptPreset?.name.orEmpty()
    ) {
        LazyColumn {
            item {
                ClickableSettingItem(
                    title = stringResource(R.string.ai_skill_export),
                    onClick = {
                        selectedPromptPreset?.let { onIntent(SkillsIntent.ExportPromptPreset(it.id)) }
                        selectedPromptPreset = null
                    }
                )
                if (selectedPromptPreset?.builtIn == false) {
                    ClickableSettingItem(
                        title = stringResource(R.string.ai_skill_delete),
                        onClick = {
                            selectedPromptPreset?.let { onIntent(SkillsIntent.DeletePromptPreset(it.id)) }
                            selectedPromptPreset = null
                        }
                    )
                }
            }
        }
    }

    // 新建：先选任务类型
    AppModalBottomSheet(
        show = state.creating && state.createTaskType.isBlank(),
        onDismissRequest = { onIntent(SkillsIntent.DismissCreate) },
        title = stringResource(R.string.ai_skill_select_task)
    ) {
        LazyColumn {
            items(createTaskTypes, key = { it }) { taskType ->
                ClickableSettingItem(
                    title = aiTaskSceneLabel(taskType) ?: taskType,
                    onClick = { onIntent(SkillsIntent.SetCreateTaskType(taskType)) }
                )
            }
        }
    }

    // 新建：名称与提示词
    AppModalBottomSheet(
        show = state.creating && state.createTaskType.isNotBlank(),
        onDismissRequest = { onIntent(SkillsIntent.DismissCreate) },
        title = stringResource(
            R.string.ai_skill_create_for,
            aiTaskSceneLabel(state.createTaskType) ?: state.createTaskType
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            AppTextField(
                value = state.createName,
                onValueChange = { onIntent(SkillsIntent.SetCreateName(it)) },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.ai_skill_name),
                singleLine = true
            )
            AppTextField(
                value = state.createPrompt,
                onValueChange = { onIntent(SkillsIntent.SetCreatePrompt(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                label = stringResource(R.string.ai_skill_prompt),
                minLines = 4,
                maxLines = 10
            )
            ConfirmDismissButtonsRow(
                onDismiss = { onIntent(SkillsIntent.DismissCreate) },
                onConfirm = { onIntent(SkillsIntent.SaveCreate) },
                dismissText = stringResource(R.string.cancel),
                confirmText = stringResource(R.string.save)
            )
        }
    }

    // 导入预览：提示词全文是一等公民，条目点击展开
    AppModalBottomSheet(
        show = state.importItems.isNotEmpty(),
        onDismissRequest = { onIntent(SkillsIntent.DismissImport) },
        title = stringResource(R.string.ai_skill_import_title)
    ) {
        LazyColumn(
            modifier = Modifier.heightIn(max = 460.dp)
        ) {
            item {
                Text(
                    text = state.importPackName,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(state.importItems, key = { it.index }) { item ->
                ImportItemRow(item = item, onToggle = { onIntent(SkillsIntent.ToggleImportItem(item.index)) })
            }
            item {
                ConfirmDismissButtonsRow(
                    onDismiss = { onIntent(SkillsIntent.DismissImport) },
                    onConfirm = { onIntent(SkillsIntent.ConfirmImport) },
                    dismissText = stringResource(R.string.cancel),
                    confirmText = stringResource(R.string.ai_skill_import_confirm)
                )
            }
        }
    }
}

@Composable
private fun ImportItemRow(
    item: AiSkillImportItemUi,
    onToggle: () -> Unit
) {
    var expanded by remember(item.index) { mutableStateOf(false) }
    Column {
        ClickableSettingItem(
            title = item.name,
            description = buildString {
                append(item.typeLabel)
                append(" · ")
                append(item.taskLabel)
                if (item.paramsSummary.isNotBlank()) {
                    append(" · ")
                    append(item.paramsSummary)
                }
                if (item.modelMatched) {
                    append(" · ")
                    append(item.modelLabel)
                } else {
                    append(" · ")
                    append(stringResource(R.string.ai_skill_model_missing))
                }
                if (item.sameContent) {
                    append(" · ")
                    append(stringResource(R.string.ai_skill_same_content))
                }
            },
            trailingContent = {
                Checkbox(checked = item.selected, onCheckedChange = { onToggle() })
            },
            onClick = { expanded = !expanded }
        )
        AnimatedVisibility(visible = expanded) {
            Text(
                text = item.prompt,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}
