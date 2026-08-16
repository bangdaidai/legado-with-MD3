package io.legado.app.ui.main.homepage.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import io.legado.app.R
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.domain.model.HomepageModuleType
import io.legado.app.domain.model.ModuleDef
import io.legado.app.ui.main.homepage.HomepageViewModel
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.JsonConfigEditor
import io.legado.app.ui.widget.components.JsonRawEditor
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.SecondaryButton
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.divider.PillHeaderDivider
import io.legado.app.ui.widget.components.explore.ExploreKindSelectSheet
import io.legado.app.ui.widget.components.settingItem.CompactClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.CompactDropdownSettingItem
import io.legado.app.ui.widget.components.text.AppText

data class AddDialogPrefill(
    val title: String = "",
    val url: String = "",
    val type: String = "card"
)

/** 从 prefillArgs 或 prefillTitle/Url 重建已选分类列表（编辑时回显用） */
private fun reconstructSelectedKinds(
    prefillArgs: String,
    prefillTitle: String,
    prefillUrl: String,
): List<ExploreKind> {
    // 尝试从 args JSON 解析多分类
    if (prefillArgs.isNotBlank()) {
        try {
            val obj = com.google.gson.JsonParser.parseString(prefillArgs).asJsonObject
            // 兼容两种字段名：正常的 kindTitles/kindUrls 和混淆后的 a/b
            val titlesKey = when {
                obj.has("kindTitles") -> "kindTitles"
                obj.has("a") -> "a"
                else -> null
            }
            val urlsKey = when {
                obj.has("kindUrls") -> "kindUrls"
                obj.has("b") -> "b"
                else -> null
            }
            if (titlesKey != null) {
                val titles = obj.getAsJsonArray(titlesKey).mapNotNull { el ->
                    if (el.isJsonNull) null else el.asString
                }
                val urls = if (urlsKey != null) {
                    obj.getAsJsonArray(urlsKey).map { el ->
                        if (el.isJsonNull) null else el.asString
                    }
                } else emptyList()
                if (titles.isNotEmpty()) {
                    return titles.mapIndexed { i, t ->
                        ExploreKind(title = t, url = urls.getOrNull(i))
                    }
                }
            }
        } catch (_: Exception) {
            // JSON parse failure, fall through
        }
    }
    // 单分类或组合标题回显
    if (prefillTitle.isNotBlank()) {
        // 组合标题（A·B·C）拆开成多个分类
        if ("·" in prefillTitle) {
            val parts = prefillTitle.split("·").map { it.trim() }.filter { it.isNotBlank() }
            if (parts.size >= 2) {
                return parts.map { ExploreKind(title = it, url = null) }
            }
        }
        return listOf(ExploreKind(title = prefillTitle, url = prefillUrl.ifBlank { null }))
    }
    return emptyList()
}

/**
 * 把已选分类写回 args。args 才是真正持久化并被 HomepageViewModel 读取的字段，
 * 只改 selectedKinds 不同步 args 的话，删掉的分类保存后还会回来。
 * 保持原 JSON 形状：对象(排行分组 kindTitles/kindUrls)或数组(按钮组只有标题)。
 */
private fun syncArgsWithKinds(currentArgs: String, kinds: List<ExploreKind>): String {
    if (currentArgs.isBlank()) return currentArgs
    return runCatching {
        val root = JsonParser.parseString(currentArgs)
        when {
            root.isJsonObject -> {
                val obj = root.asJsonObject
                val titlesKey = when {
                    obj.has("kindTitles") -> "kindTitles"
                    obj.has("a") -> "a"
                    else -> return currentArgs
                }
                val urlsKey = when {
                    obj.has("kindUrls") -> "kindUrls"
                    obj.has("b") -> "b"
                    else -> null
                }
                obj.add(titlesKey, JsonArray().apply { kinds.forEach { add(it.title) } })
                urlsKey?.let { key ->
                    obj.add(key, JsonArray().apply {
                        kinds.forEach { k -> k.url?.let { add(it) } ?: add(JsonNull.INSTANCE) }
                    })
                }
                obj.toString()
            }

            root.isJsonArray -> JsonArray().apply { kinds.forEach { add(it.title) } }.toString()
            else -> currentArgs
        }
    }.getOrDefault(currentArgs)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> AddCustomModuleDialog(
    data: T?,
    sourceUrl: String = "",
    targetSetId: String = "",
    prefillTitle: String = "",
    prefillUrl: String = "",
    prefillType: String = "card",
    prefillArgs: String = "",
    prefillLayoutConfig: String = "",
    canSelectInfinite: Boolean = true,
    onDismissRequest: () -> Unit,
    onConfirm: (ModuleDef) -> Unit,
) {
    var title by remember(data) { mutableStateOf(prefillTitle) }
    var url by remember(data) { mutableStateOf(prefillUrl) }
    var type by remember(data) { mutableStateOf(prefillType) }
    var args by remember(data) { mutableStateOf(prefillArgs) }
    var layoutConfig by remember(data) { mutableStateOf(prefillLayoutConfig) }
    var showRawLayoutConfig by remember(data) { mutableStateOf(false) }
    // 保存完整的 ExploreKind，同名分类靠 url 区分；编辑时从 args/标题回显
    var selectedKinds by remember(data) {
        mutableStateOf(reconstructSelectedKinds(prefillArgs, prefillTitle, prefillUrl))
    }
    var showKindSelect by remember(data) { mutableStateOf(false) }

    val hasVisualizableKeys = remember(layoutConfig) {
        runCatching {
            val jsonObject = JsonParser.parseString(layoutConfig).asJsonObject
            jsonObject.keySet().any { key ->
                key == "columns" || key == "rows"
            }
        }.getOrElse { false }
    }

    AppAlertDialog(
        data = data,
        onDismissRequest = onDismissRequest,
        title = if (prefillTitle.isEmpty()) stringResource(R.string.homepage_add_module) else stringResource(
            R.string.homepage_edit_module
        ),
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AppTextField(
                    value = title,
                    onValueChange = { title = it },
                    backgroundColor = LegadoTheme.colorScheme.onSheetContent,
                    label = stringResource(R.string.homepage_title_label),
                    modifier = Modifier.fillMaxWidth()
                )
                AppTextField(
                    value = url,
                    onValueChange = { url = it },
                    backgroundColor = LegadoTheme.colorScheme.onSheetContent,
                    label = "URL",
                    modifier = Modifier.fillMaxWidth()
                )
                val typeList = remember(canSelectInfinite) {
                    HomepageModuleType.entries.filter {
                        it != HomepageModuleType.Unknown && (canSelectInfinite || !HomepageViewModel.isInfinite(
                            it.key,
                            null
                        ))
                    }
                }

                GlassCard(
                    containerColor = LegadoTheme.colorScheme.onSheetContent
                ) {
                    CompactDropdownSettingItem(
                        title = stringResource(R.string.homepage_type_label),
                        selectedValue = type,
                        displayEntries = typeList.map { it.title }.toTypedArray(),
                        entryValues = typeList.map { it.key }.toTypedArray(),
                        onValueChange = { type = it }
                    )
                }

                if (HomepageViewModel.isInfinite(type, null) && !canSelectInfinite) {
                    AppText(
                        text = stringResource(R.string.homepage_module_duplicate_infinite),
                        color = LegadoTheme.colorScheme.error,
                        style = LegadoTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                // 已选分类
                AppText(
                    text = stringResource(R.string.homepage_selected_categories),
                    style = LegadoTheme.typography.labelMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (selectedKinds.isEmpty()) {
                        AppText(
                            text = stringResource(R.string.homepage_no_category_selected),
                            style = LegadoTheme.typography.bodySmall,
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        selectedKinds.forEachIndexed { index, kind ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = LegadoTheme.colorScheme.primaryContainer,
                                contentColor = LegadoTheme.colorScheme.onPrimaryContainer,
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        start = 12.dp,
                                        end = 4.dp,
                                        top = 4.dp,
                                        bottom = 4.dp
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AppText(
                                        text = kind.title,
                                        style = LegadoTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    IconButton(
                                        onClick = {
                                            selectedKinds = selectedKinds
                                                .toMutableList()
                                                .apply { removeAt(index) }
                                            args = syncArgsWithKinds(args, selectedKinds)
                                        },
                                        modifier = Modifier.size(20.dp),
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 选择/修改分类按钮
                SecondaryButton(
                    text = if (selectedKinds.isEmpty()) stringResource(R.string.homepage_select_category)
                    else stringResource(R.string.homepage_modify_category),
                    onClick = { showKindSelect = true },
                    modifier = Modifier.fillMaxWidth()
                )

                AppTextField(
                    value = args,
                    onValueChange = { args = it },
                    backgroundColor = LegadoTheme.colorScheme.onSheetContent,
                    label = "Args (JSON)",
                    modifier = Modifier.fillMaxWidth()
                )

                PillHeaderDivider(
                    title = stringResource(R.string.homepage_layout_config_label)
                )

                if (hasVisualizableKeys) {
                    JsonConfigEditor(
                        jsonString = layoutConfig,
                        onJsonStringChange = { layoutConfig = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    CompactClickableSettingItem(
                        title = stringResource(R.string.homepage_edit_raw_json),
                        onClick = { showRawLayoutConfig = !showRawLayoutConfig }
                    )
                    if (showRawLayoutConfig) {
                        JsonRawEditor(
                            value = layoutConfig,
                            onValueChange = { layoutConfig = it },
                            label = "LayoutConfig (JSON) RAW",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    JsonRawEditor(
                        value = layoutConfig,
                        onValueChange = { layoutConfig = it },
                        label = "LayoutConfig (JSON)",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        onConfirm = {
            onConfirm(
                ModuleDef(
                    title = title,
                    url = url,
                    type = type,
                    args = args,
                    layoutConfig = layoutConfig
                )
            )
        },
        confirmText = stringResource(R.string.dialog_confirm),
        dismissText = stringResource(R.string.dialog_cancel),
        onDismiss = onDismissRequest
    )

    if (showKindSelect) {
        ExploreKindSelectSheet(
            show = true,
            onDismissRequest = { showKindSelect = false },
            sourceUrl = sourceUrl,
            multiple = true,
            initialSelectedTitles = selectedKinds.map { it.title },
            initialSelectedUrls = selectedKinds.map { it.url },
            onSelected = { kinds ->
                selectedKinds = kinds
                args = syncArgsWithKinds(args, kinds)
                if (kinds.size >= 2) {
                    title = kinds.joinToString("·") { it.title }
                } else if (kinds.size == 1) {
                    title = kinds.first().title
                    kinds.first().url?.let { url = it }
                }
                showKindSelect = false
            }
        )
    }
}
