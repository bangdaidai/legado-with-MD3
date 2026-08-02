package io.legado.app.ui.widget.components.explore

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.data.repository.ExploreRepository
import io.legado.app.domain.usecase.ExploreKindUiUseCase
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.ThemeResolver
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreKindSelectSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    sourceUrl: String?,
    onSelected: (List<ExploreKind>) -> Unit,
    multiple: Boolean = false,
    initialSelectedTitles: List<String> = emptyList(),
    initialSelectedUrls: List<String?> = emptyList(),
    repository: ExploreRepository = koinInject(),
    useCase: ExploreKindUiUseCase = koinInject()
) {
    var kinds by remember { mutableStateOf<List<ExploreKind>>(emptyList()) }
    // 用列表下标作为唯一标识，同名（甚至同名同 url）的分类也能独立选中
    var selectedIndices by remember(show) { mutableStateOf<Set<Int>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    val context = LocalContext.current
    val activity = context as? AppCompatActivity
    val isMiuix = ThemeResolver.isMiuixEngine(LegadoTheme.composeEngine)

    LaunchedEffect(show, sourceUrl) {
        if (show && !sourceUrl.isNullOrBlank()) {
            val loadedKinds = repository.getSourceExploreKinds(sourceUrl)
            kinds = loadedKinds
            // kinds 加载完后再做初始选中匹配
            val usedIndices = mutableSetOf<Int>()
            val initIndices = mutableSetOf<Int>()
            for (i in initialSelectedTitles.indices) {
                val wantTitle = initialSelectedTitles[i]
                val wantUrl = initialSelectedUrls.getOrNull(i)
                // 优先按 url 精确匹配（跳过已占用的下标）
                val byUrl = if (!wantUrl.isNullOrBlank()) {
                    loadedKinds.indices.firstOrNull { idx ->
                        idx !in usedIndices && loadedKinds[idx].url == wantUrl
                    }
                } else null
                val idx = byUrl ?: run {
                    // 按 title 匹配，跳过已占用的下标
                    loadedKinds.indices.firstOrNull { idx ->
                        idx !in usedIndices && loadedKinds[idx].title == wantTitle
                    }
                }
                if (idx != null) {
                    initIndices.add(idx)
                    usedIndices.add(idx)
                }
            }
            selectedIndices = initIndices
        }
    }

    // 过滤后仍需知道每项在原列表中的下标
    val filteredKinds = remember(query, kinds) {
        if (query.isBlank()) kinds
        else kinds.filter { kind ->
            kind.title.contains(query, ignoreCase = true) ||
                    (kind.url?.contains(query, ignoreCase = true) == true)
        }
    }
    val kindRows = remember(filteredKinds) {
        calculateExploreKindRows(filteredKinds, 6)
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        endAction = {
            if (multiple && selectedIndices.isNotEmpty()) {
                MediumTonalButton(
                    onClick = {
                        val selectedKinds = selectedIndices.sorted()
                            .mapNotNull { kinds.getOrNull(it) }
                        onSelected(selectedKinds)
                        onDismissRequest()
                    },
                    icon = Icons.Default.Check,
                    contentDescription = stringResource(R.string.confirm)
                )
            }
        }
    ) {
        Column {
            SearchBar(
                query = query,
                backgroundColor = LegadoTheme.colorScheme.onSheetContent,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.select_or_search_category),
                autoFocus = false
            )

            LazyColumn(
                contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                items(kindRows) { rowItems ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .animateItem()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { (kind, span) ->
                            // 该项在原始 kinds 列表中的下标（用引用相等定位，保证同名项不串）
                            val kindIndex = remember(kinds, kind) {
                                kinds.indexOfFirst { it === kind }
                            }
                            val isSelected = kindIndex in selectedIndices
                            ExploreKindMultiTypeItem(
                                modifier = Modifier
                                    .weight(span.toFloat())
                                    .animateItem(),
                                kind = kind,
                                sourceUrl = sourceUrl,
                                activity = activity,
                                onOpenUrl = { url ->
                                    if (!multiple) {
                                        onSelected(listOf(kind.copy(url = url)))
                                        onDismissRequest()
                                    }
                                },
                                isSelected = isSelected,
                                onClick = {
                                    if (multiple) {
                                        if (kindIndex < 0) return@ExploreKindMultiTypeItem
                                        selectedIndices = if (isSelected) {
                                            selectedIndices - kindIndex
                                        } else {
                                            selectedIndices + kindIndex
                                        }
                                    } else {
                                        onSelected(listOf(kind))
                                        onDismissRequest()
                                    }
                                },
                                backgroundColor = LegadoTheme.colorScheme.surface.copy(alpha = 0.5f),
                                isMiuix = isMiuix,
                                useCase = useCase
                            )
                        }

                        val totalSpan = rowItems.sumOf { it.second }
                        if (totalSpan < 6) {
                            Spacer(
                                modifier = Modifier.weight((6 - totalSpan).toFloat())
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分类唯一标识：同名分类靠 url 区分 */
fun exploreKindKey(kind: ExploreKind): String = "${kind.title}||${kind.url}"
