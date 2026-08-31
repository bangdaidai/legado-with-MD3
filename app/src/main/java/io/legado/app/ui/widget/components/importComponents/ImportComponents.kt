package io.legado.app.ui.widget.components.importComponents

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import androidx.compose.runtime.livedata.observeAsState
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.card.SelectionItemCard
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.progressIndicator.AppCircularProgressIndicator
import io.legado.app.ui.widget.components.settingItem.SwitchSettingItem
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.utils.GSON

@Composable
fun SourceInputDialog(
    show: Boolean,
    title: String = "网络导入",
    hint: String = "请输入 URL 或 JSON",
    initialValue: String = "",
    historyValues: List<String> = emptyList(),
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(show) { mutableStateOf(initialValue) }

    AppAlertDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        title = title,
        content = {
            Column {
                AppTextField(
                    value = text,
                    onValueChange = { text = it },
                    backgroundColor = LegadoTheme.colorScheme.onSheetContent,
                    label = hint,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5
                )

                if (historyValues.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    AppText(stringResource(R.string.history_label), style = LegadoTheme.typography.labelSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(historyValues) { history ->
                            AssistChip(
                                onClick = { text = history },
                                label = { AppText(history, maxLines = 1) }
                            )
                        }
                    }
                }
            }
        },
        confirmText = stringResource(android.R.string.ok),
        onConfirm = {
            // 拦截空输入，非空才执行回调
            if (text.isNotBlank()) onConfirm(text)
        },
        dismissText = stringResource(android.R.string.cancel),
        onDismiss = onDismissRequest
    )
}

/**
 * 由遗留导入 ViewModel 的三组数据（全部条目、已存在对照、选中状态）构建统一的导入 UI 状态。
 * [statusOf] 用于计算单条目的新增/更新/已有状态，随各实体类型不同。
 */
fun <T> associationImportState(
    error: String?,
    loaded: Boolean,
    items: List<T>,
    existing: List<T?>,
    selected: List<Boolean>,
    statusOf: (existing: T?, incoming: T) -> ImportStatus
): BaseImportUiState<T> {
    return when {
        error != null -> BaseImportUiState.Error(error)
        !loaded -> BaseImportUiState.Loading
        else -> BaseImportUiState.Success(
            source = "",
            items = items.mapIndexed { index, data ->
                ImportItemWrapper(
                    data = data,
                    oldData = existing.getOrNull(index),
                    isSelected = selected.getOrNull(index) ?: true,
                    status = statusOf(existing.getOrNull(index), data)
                )
            }
        )
    }
}

/**
 * 遗留导入对话框（文件/订阅关联入口）共用的 Compose 承载内容。
 * 直接以 [BatchImportDialog] 的 dialog 形态呈现，复用项目的选择/编辑/导入规范。
 *
 * @param items 全部待导入条目
 * @param existing 与本地已存在条目的对照（可空）
 * @param selected 选中状态列表（可变，直接在此处翻转）
 * @param onImportSelect 提交导入，参数为导入完成后的回调
 * @param statusOf 计算单条新增/更新/已有状态
 */
@Composable
fun <T> ImportAssociationContent(
    title: String,
    items: List<T>,
    existing: List<T?>,
    selected: MutableList<Boolean>,
    errorLiveData: LiveData<String>,
    successLiveData: LiveData<Int>,
    onImportSelect: (finally: () -> Unit) -> Unit,
    statusOf: (existing: T?, incoming: T) -> ImportStatus,
    itemTitle: (T) -> String,
    itemSubtitle: (T) -> String? = { null },
    onUpdateItem: (Int, T) -> Unit = { _, _ -> },
    topBarActions: @Composable RowScope.() -> Unit = {},
    onDismissRequest: () -> Unit
) {
    val error by errorLiveData.observeAsState()
    val successCount by successLiveData.observeAsState(-1)
    var refresh by remember { mutableIntStateOf(0) }

    val importState = remember(refresh, error, successCount) {
        associationImportState(
            error = error,
            loaded = successCount >= 0,
            items = items,
            existing = existing,
            selected = selected,
            statusOf = statusOf
        )
    }

    BatchImportDialog(
        asDialog = true,
        title = title,
        importState = importState,
        onDismissRequest = onDismissRequest,
        onConfirm = { onImportSelect(onDismissRequest) },
        onToggleItem = { index ->
            refresh++
            selected[index] = !selected[index]
        },
        onToggleAll = { selectAll ->
            refresh++
            selected.forEachIndexed { index, _ -> selected[index] = selectAll }
        },
        onUpdateItem = { index, data ->
            refresh++
            onUpdateItem(index, data)
        },
        topBarActions = topBarActions,
        itemTitle = itemTitle,
        itemSubtitle = itemSubtitle
    )
}

//TODO: 动画
@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> BatchImportDialog(
    title: String,
    importState: BaseImportUiState<T>,
    onDismissRequest: () -> Unit,
    onConfirm: (List<T>) -> Unit,
    onToggleItem: (index: Int) -> Unit,
    onToggleAll: (isSelected: Boolean) -> Unit,
    /** 在 DialogFragment 等宿主中直接以 AppAlertDialog 形态呈现成功态，而非底部抽屉。 */
    asDialog: Boolean = false,
    onItemInfoClick: (index: Int) -> Unit = {},
    onUpdateItem: (index: Int, data: T) -> Unit = { _, _ -> },
    topBarActions: @Composable RowScope.() -> Unit = {},
    itemTitle: (data: T) -> String,
    itemSubtitle: (data: T) -> String? = { null }
) {
    AppAlertDialog(
        data = importState as? BaseImportUiState.Loading,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.loading),
        content = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                AppCircularProgressIndicator()
            }
        }
    )

    AppAlertDialog(
        data = importState as? BaseImportUiState.Error,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.error),
        onConfirm = { onDismissRequest() },
        content = { error ->
            AppText(error.msg)
        }
    )

    val show = importState is BaseImportUiState.Success<T>

    var cachedState by remember { mutableStateOf<BaseImportUiState.Success<T>?>(null) }
    if (importState is BaseImportUiState.Success<T>) {
        cachedState = importState
    }

    if (!show && cachedState == null) return

    val currentState = cachedState!!
    var editingIndex by remember(currentState.source) { mutableStateOf<Int?>(null) }
    val editingItem = editingIndex?.let { currentState.items.getOrNull(it) }
    val isEditing = editingItem != null
    val selectedCount = currentState.items.count { it.isSelected }
    val totalCount = currentState.items.size
    val allSelected = selectedCount == totalCount
    val sheetTitle = when {
        isEditing -> itemTitle(editingItem.data)
        selectedCount > 0 -> {
            stringResource(
                R.string.select_count,
                selectedCount,
                totalCount
            )
        }

        else -> title
    }

    val selectedData = currentState.items.filter { it.isSelected }.map { it.data }

    val contentBody: @Composable () -> Unit = {
        if (isEditing) {
            BatchImportJsonEditContent(
                data = editingItem.data,
                version = currentState.version
            ) { data ->
                editingIndex?.let { onUpdateItem(it, data) }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.58f)
            ) {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(
                        currentState.items,
                        key = { index, _ -> index }
                    ) { index, itemWrapper ->
                        ImportItemRow(
                            title = itemTitle(itemWrapper.data),
                            subtitle = itemSubtitle(itemWrapper.data),
                            isSelected = itemWrapper.isSelected,
                            status = itemWrapper.status,
                            onClick = { onToggleItem(index) },
                            onInfoClick = {
                                onItemInfoClick(index)
                                editingIndex = index
                            }
                        )
                    }
                }
            }
        }

        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(8.dp)
        )
    }

    if (asDialog) {
        AppAlertDialog(
            show = show,
            onDismissRequest = onDismissRequest,
            title = sheetTitle,
            titleAction = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isEditing) {
                        MediumTonalButton(
                            onClick = { editingIndex = null },
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    } else {
                        topBarActions()
                        MediumTonalButton(
                            onClick = { onToggleAll(!allSelected) },
                            icon = Icons.Default.SelectAll,
                            contentDescription = stringResource(if (allSelected) R.string.deselect_all else R.string.select_all)
                        )
                    }
                }
            },
            confirmText = stringResource(R.string.import_action),
            onConfirm = if (!isEditing && selectedCount > 0) {
                { onConfirm(selectedData) }
            } else null,
            dismissText = stringResource(android.R.string.cancel),
            onDismiss = onDismissRequest,
            content = contentBody
        )
    } else {
        AppModalBottomSheet(
            show = show,
            onDismissRequest = onDismissRequest,
            modifier = Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.8f),
            title = sheetTitle,
            startAction = if (isEditing) {
                {
                    MediumTonalButton(
                        onClick = { editingIndex = null },
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
            } else {
                {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        topBarActions()
                        MediumTonalButton(
                            onClick = { onToggleAll(!allSelected) },
                            icon = Icons.Default.SelectAll,
                            contentDescription = stringResource(if (allSelected) R.string.deselect_all else R.string.select_all)
                        )
                    }
                }
            },
            endAction = if (!isEditing && selectedCount > 0) {
                {
                    MediumTonalButton(
                        onClick = { onConfirm(selectedData) },
                        icon = Icons.Default.FileDownload,
                        text = stringResource(R.string.import_action)
                    )
                }
            } else {
                null
            }
        ) {
            contentBody()
        }
    }
}

@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun <T> BatchImportJsonEditContent(
    data: T,
    version: Int,
    onDataChange: (T) -> Unit
) {
    val jsonObject = remember(version) { data.toImportJsonObject() }

    if (jsonObject == null) {
        AppText(stringResource(R.string.edit_not_supported))
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.58f),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = jsonObject.entrySet().toList(),
            key = { it.key }
        ) { entry ->
            BatchImportJsonField(
                name = entry.key,
                value = entry.value,
                onValueChange = { value ->
                    val updatedJsonObject = data.toImportJsonObject() ?: return@BatchImportJsonField
                    updatedJsonObject.add(entry.key, value)
                    updatedJsonObject.toImportDataLike(data)?.let(onDataChange)
                }
            )
        }
    }
}

@Composable
private fun BatchImportJsonField(
    name: String,
    value: JsonElement,
    onValueChange: (JsonElement) -> Unit
) {
    val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
    if (primitive?.isBoolean == true) {
        GlassCard(
            containerColor = LegadoTheme.colorScheme.onSheetContent
        ) {
            SwitchSettingItem(
                title = name,
                checked = primitive.asBoolean,
                onCheckedChange = { onValueChange(JsonPrimitive(it)) }
            )
        }

        return
    }

    val isJsonText = value.isJsonObject || value.isJsonArray
    val initialText = value.toImportEditText()
    var text by remember(name, initialText) { mutableStateOf(initialText) }

    AppTextField(
        value = text,
        onValueChange = { newText ->
            text = newText
            newText.toImportJsonElement(value)?.let(onValueChange)
        },
        label = name,
        modifier = Modifier.fillMaxWidth(),
        singleLine = !isJsonText,
        maxLines = if (isJsonText) 8 else 1
    )
}

@Composable
fun ImportItemRow(
    title: String,
    subtitle: String? = null,
    isSelected: Boolean,
    status: ImportStatus,
    onClick: () -> Unit,
    onInfoClick: () -> Unit
) {
    SelectionItemCard(
        title = title,
        subtitle = subtitle,
        isSelected = isSelected,
        inSelectionMode = true,
        onToggleSelection = onClick,
        containerColor = LegadoTheme.colorScheme.onSheetContent,
        trailingAction = {
            AppText(
                text = when (status) {
                    ImportStatus.New -> stringResource(R.string.import_status_new)
                    ImportStatus.Update -> stringResource(R.string.import_status_update)
                    ImportStatus.Existing -> stringResource(R.string.import_status_existing)
                    ImportStatus.Error -> stringResource(R.string.import_status_error)
                },
                style = LegadoTheme.typography.labelMedium,
                color = when (status) {
                    ImportStatus.New -> LegadoTheme.colorScheme.primary
                    ImportStatus.Update -> LegadoTheme.colorScheme.secondary
                    ImportStatus.Error -> LegadoTheme.colorScheme.error
                    else -> LegadoTheme.colorScheme.outline
                },
                modifier = Modifier.padding(end = 4.dp)
            )

            SmallPlainButton(
                onClick = onInfoClick,
                icon = Icons.Default.Info,
                contentDescription = stringResource(R.string.details)
            )
        }
    )
}

private fun Any?.toImportJsonObject(): JsonObject? {
    return GSON.toJsonTree(this).takeIf { it.isJsonObject }?.asJsonObject
}

@Suppress("UNCHECKED_CAST")
private fun <T> JsonObject.toImportDataLike(data: T): T? {
    val clazz = data?.let { it::class.java } ?: return null
    return runCatching { GSON.fromJson(this, clazz) as T }.getOrNull()
}

private fun JsonElement.toImportEditText(): String {
    return when {
        this is JsonNull || isJsonNull -> ""
        isJsonObject || isJsonArray -> GSON.toJson(this)
        isJsonPrimitive -> asJsonPrimitive.asString
        else -> toString()
    }
}

private fun String.toImportJsonElement(oldValue: JsonElement): JsonElement? {
    val text = trim()
    if (oldValue.isJsonNull) {
        return if (text.isEmpty()) JsonNull.INSTANCE else JsonPrimitive(this)
    }

    if (oldValue.isJsonObject || oldValue.isJsonArray) {
        if (text.isEmpty()) return JsonNull.INSTANCE
        return runCatching { JsonParser.parseString(this) }.getOrNull()
    }

    if (!oldValue.isJsonPrimitive) return JsonPrimitive(this)

    val primitive = oldValue.asJsonPrimitive
    return when {
        primitive.isNumber -> {
            if (text.isEmpty()) {
                JsonNull.INSTANCE
            } else {
                text.toLongOrNull()?.let { JsonPrimitive(it) }
                    ?: text.toDoubleOrNull()?.let { JsonPrimitive(it) }
            }
        }

        else -> JsonPrimitive(this)
    }
}
