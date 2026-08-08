package io.legado.app.ui.book.tagmanage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.widget.Toast
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.data.entities.ExcludedTag
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.LocalAppUiConfiguration
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import kotlinx.coroutines.flow.collectLatest
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ExcludedTagScreen(
    state: ExcludedTagUiState,
    onIntent: (ExcludedTagIntent) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    var showSearch by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf<ExcludedTag?>(null) }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "排除标签",
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                actions = {
                    TopBarActionButton(
                        onClick = {
                            showSearch = !showSearch
                            if (!showSearch) onIntent(ExcludedTagIntent.Search(""))
                        },
                        imageVector = AppIcons.Search,
                        contentDescription = "搜索",
                    )
                    TopBarActionButton(
                        onClick = { edit = ExcludedTag(name = "", isRegex = false) },
                        imageVector = AppIcons.Add,
                        contentDescription = "新增排除项",
                    )
                },
                bottomContent = {
                    AnimatedVisibility(
                        modifier = Modifier.adaptiveHorizontalPadding(),
                        visible = showSearch,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        SearchBar(
                            query = state.searchQuery,
                            onQueryChange = { onIntent(ExcludedTagIntent.Search(it)) },
                            placeholder = "搜索排除项",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(padding.calculateTopPadding()))
            val query = state.searchQuery
            val filtered = if (query.isBlank()) {
                state.excludedTags
            } else {
                state.excludedTags.filter { it.name.contains(query, ignoreCase = true) }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                val themeSettings = LocalAppUiConfiguration.current.theme
                val resolvedCornerRadius = if (themeSettings.overrideBaseCardCornerRadius) {
                    themeSettings.baseCardCornerRadius.dp
                } else {
                    8.dp
                }
                val resolvedShape = RoundedCornerShape(resolvedCornerRadius)
                // 排除项用主题的 error 色表达「被屏蔽」语义
                val excludedAccent = LegadoTheme.colorScheme.error
                val borderModifier = if (state.bookshelfTagBorder) {
                    Modifier.border(
                        BorderStroke(0.5.dp, excludedAccent),
                        resolvedShape
                    )
                } else if (themeSettings.overrideBaseCardBorder) {
                    val configuredColor = if (LegadoTheme.isDark) {
                        themeSettings.baseCardBorderColorNight
                    } else {
                        themeSettings.baseCardBorderColor
                    }
                    val borderColor = configuredColor.takeIf { it != 0 }?.let(::Color)
                        ?: LegadoTheme.colorScheme.outlineVariant
                    Modifier.border(
                        BorderStroke(themeSettings.baseCardBorderWidth.dp, borderColor),
                        resolvedShape
                    )
                } else {
                    Modifier
                }
                filtered.forEach { excluded ->
                    Row(
                        modifier = Modifier
                            .clip(resolvedShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .then(borderModifier)
                            .clickable { edit = excluded }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(excludedAccent),
                        )
                        Text(
                            excluded.name + if (excluded.isRegex) "  (正则)" else "",
                            style = LegadoTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
    }

    ExcludedEditSheet(
        data = edit,
        onSave = {
            onIntent(ExcludedTagIntent.SaveExcluded(it.id, it.name, it.isRegex))
            edit = null
        },
        onDelete = {
            onIntent(ExcludedTagIntent.DeleteExcluded(it))
            edit = null
        },
        onDismiss = { edit = null },
    )
}

@Composable
fun ExcludedTagRouteScreen(onBack: () -> Unit) {
    val viewModel = koinViewModel<ExcludedTagViewModel>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.effect.collectLatest {
            when (it) {
                is ExcludedTagEffect.ShowMessage ->
                    Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    ExcludedTagScreen(state = state, onIntent = viewModel::sendEvent, onBack = onBack)
}
