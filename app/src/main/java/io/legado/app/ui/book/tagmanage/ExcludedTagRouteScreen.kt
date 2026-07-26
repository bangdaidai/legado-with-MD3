package io.legado.app.ui.book.tagmanage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
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
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.SearchBar
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.menuItem.MenuItemIcon
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
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
    var menuExpanded by remember { mutableStateOf(false) }
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
                        onClick = { menuExpanded = true },
                        imageVector = AppIcons.MoreVert,
                        contentDescription = "更多",
                    )
                    RoundDropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) { dismiss ->
                        RoundDropdownMenuItem(
                            text = "新增排除项",
                            leadingIcon = { MenuItemIcon(Icons.Filled.Add) },
                            onClick = { dismiss(); edit = ExcludedTag(name = "", isRegex = false) },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showSearch) {
                SearchBar(
                    query = state.searchQuery,
                    onQueryChange = { onIntent(ExcludedTagIntent.Search(it)) },
                    onClose = { showSearch = false; onIntent(ExcludedTagIntent.Search("")) },
                    placeholder = "搜索排除项",
                )
            }
            val query = state.searchQuery
            val filtered = if (query.isBlank()) {
                state.excludedTags
            } else {
                state.excludedTags.filter { it.name.contains(query, ignoreCase = true) }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                filtered.forEach { excluded ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { edit = excluded }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0xFFB71C1C)),
                        )
                        Text(
                            excluded.name + if (excluded.isRegex) "  (正则)" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
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
