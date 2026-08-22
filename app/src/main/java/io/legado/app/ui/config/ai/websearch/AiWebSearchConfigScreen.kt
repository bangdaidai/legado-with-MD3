package io.legado.app.ui.config.ai.websearch

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.domain.model.settings.WebSearchSettings
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.SplicedColumnGroup
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.settingItem.ClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.DropdownListSettingItem
import io.legado.app.ui.widget.components.settingItem.InputSettingItem
import io.legado.app.ui.widget.components.settingItem.SliderSettingItem
import io.legado.app.ui.widget.components.settingItem.SwitchSettingItem
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import org.koin.androidx.compose.koinViewModel

@Composable
fun AiWebSearchConfigRouteScreen(
    onBackClick: () -> Unit,
    viewModel: AiWebSearchConfigViewModel = koinViewModel()
) {
    AiWebSearchConfigScreen(
        state = viewModel.uiState.collectAsStateWithLifecycle().value,
        effects = viewModel.effects,
        onIntent = viewModel::onIntent,
        onBackClick = onBackClick
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiWebSearchConfigScreen(
    state: AiWebSearchConfigUiState,
    effects: Flow<AiWebSearchConfigEffect>,
    onIntent: (AiWebSearchConfigIntent) -> Unit,
    onBackClick: () -> Unit
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var apiKeyVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        effects.collectLatest { effect ->
            when (effect) {
                is AiWebSearchConfigEffect.ShowMessage ->
                    snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    val settings = state.settings
    val topicValues = WebSearchSettings.topics.toTypedArray()
    val topicEntries = arrayOf(
        stringResource(R.string.ai_web_search_topic_general),
        stringResource(R.string.ai_web_search_topic_news),
        stringResource(R.string.ai_web_search_topic_finance)
    )
    val depthValues = WebSearchSettings.depths.toTypedArray()
    val depthEntries = arrayOf(
        stringResource(R.string.ai_web_search_depth_basic),
        stringResource(R.string.ai_web_search_depth_advanced)
    )

    AppScaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.ai_web_search),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBackClick)
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = adaptiveContentPadding(
                top = paddingValues.calculateTopPadding(),
                bottom = 120.dp
            )
        ) {
            item {
                SplicedColumnGroup(title = stringResource(R.string.ai_web_search_service)) {
                    SwitchSettingItem(
                        title = stringResource(R.string.ai_web_search_enable),
                        description = stringResource(R.string.ai_web_search_enable_desc),
                        checked = settings.enabled,
                        onCheckedChange = { onIntent(AiWebSearchConfigIntent.SetEnabled(it)) }
                    )
                    ClickableSettingItem(
                        title = stringResource(R.string.ai_web_search_api_key),
                        description = if (settings.apiKey.isBlank()) {
                            stringResource(R.string.ai_web_search_api_key_hint)
                        } else {
                            stringResource(R.string.ai_web_search_api_key_set)
                        },
                        onClick = {
                            apiKeyDraft = settings.apiKey
                            apiKeyVisible = false
                            showApiKeyDialog = true
                        }
                    )
                    InputSettingItem(
                        title = stringResource(R.string.ai_web_search_base_url),
                        value = settings.baseUrl,
                        defaultValue = WebSearchSettings.DEFAULT_BASE_URL,
                        onConfirm = { onIntent(AiWebSearchConfigIntent.SetBaseUrl(it)) }
                    )
                }
            }

            item {
                SplicedColumnGroup(title = stringResource(R.string.ai_param_setting)) {
                    DropdownListSettingItem(
                        title = stringResource(R.string.ai_web_search_topic),
                        selectedValue = settings.topic,
                        displayEntries = topicEntries,
                        entryValues = topicValues,
                        onValueChange = { onIntent(AiWebSearchConfigIntent.SetTopic(it)) }
                    )
                    DropdownListSettingItem(
                        title = stringResource(R.string.ai_web_search_depth),
                        selectedValue = settings.searchDepth,
                        displayEntries = depthEntries,
                        entryValues = depthValues,
                        onValueChange = { onIntent(AiWebSearchConfigIntent.SetSearchDepth(it)) }
                    )
                    SliderSettingItem(
                        title = stringResource(R.string.ai_web_search_max_results),
                        value = settings.maxResults.toFloat(),
                        defaultValue = DEFAULT_MAX_RESULTS,
                        valueRange = WebSearchSettings.MIN_RESULTS.toFloat()..
                            WebSearchSettings.MAX_RESULTS.toFloat(),
                        steps = WebSearchSettings.MAX_RESULTS - WebSearchSettings.MIN_RESULTS - 1,
                        description = settings.maxResults.toString(),
                        onValueChange = {
                            onIntent(AiWebSearchConfigIntent.SetMaxResults(it.toInt()))
                        }
                    )
                    ClickableSettingItem(
                        title = if (state.testing) {
                            stringResource(R.string.ai_web_search_testing)
                        } else {
                            stringResource(R.string.ai_web_search_test)
                        },
                        description = stringResource(R.string.ai_web_search_test_desc),
                        onClick = {
                            if (!state.testing) {
                                onIntent(AiWebSearchConfigIntent.RunTestSearch)
                            }
                        }
                    )
                }
            }
        }
    }

    AppAlertDialog(
        show = showApiKeyDialog,
        onDismissRequest = { showApiKeyDialog = false },
        title = stringResource(R.string.ai_web_search_api_key),
        content = {
            Column {
                AppTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = LegadoTheme.colorScheme.surface,
                    label = stringResource(R.string.ai_web_search_api_key),
                    visualTransformation = if (apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Icon(
                                imageVector = if (apiKeyVisible) {
                                    Icons.Filled.Visibility
                                } else {
                                    Icons.Filled.VisibilityOff
                                },
                                contentDescription = if (apiKeyVisible) {
                                    stringResource(R.string.hide_password)
                                } else {
                                    stringResource(R.string.show_password)
                                }
                            )
                        }
                    }
                )
            }
        },
        confirmText = stringResource(R.string.ok),
        onConfirm = {
            onIntent(AiWebSearchConfigIntent.SetApiKey(apiKeyDraft))
            showApiKeyDialog = false
        },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { showApiKeyDialog = false }
    )
}

private const val DEFAULT_MAX_RESULTS = 5f
