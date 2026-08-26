package io.legado.app.ui.config.protagonistExtractionConfig

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.domain.model.settings.ProtagonistExtractionSettings
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.SplicedColumnGroup
import io.legado.app.ui.widget.components.settingItem.InputSettingItem
import io.legado.app.ui.widget.components.settingItem.SwitchSettingItem
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtagonistExtractionConfigScreen(
    state: ProtagonistExtractionConfigUiState,
    onIntent: (ProtagonistExtractionConfigIntent) -> Unit,
    onBackClick: () -> Unit,
) {
    val settings = state.settings
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.protagonist_extraction_config),
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = onBackClick) },
                actions = {
                    TextButton(onClick = { onIntent(ProtagonistExtractionConfigIntent.RestoreDefaults) }) {
                        Text(stringResource(R.string.restore_defaults))
                    }
                },
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = adaptiveContentPadding(
                top = paddingValues.calculateTopPadding(),
                bottom = 120.dp
            )
        ) {
            item {
                SplicedColumnGroup {
                    InputSettingItem(
                        title = stringResource(R.string.protagonist_extraction_prefix),
                        value = settings.protagonistPrefix,
                        onConfirm = { onIntent(ProtagonistExtractionConfigIntent.SetProtagonistPrefix(it)) }
                    )
                    InputSettingItem(
                        title = stringResource(R.string.protagonist_extraction_supporting_prefix),
                        value = settings.supportingPrefix,
                        onConfirm = { onIntent(ProtagonistExtractionConfigIntent.SetSupportingPrefix(it)) }
                    )
                    InputSettingItem(
                        title = stringResource(R.string.protagonist_extraction_separators),
                        value = settings.separators,
                        onConfirm = { onIntent(ProtagonistExtractionConfigIntent.SetSeparators(it)) }
                    )
                    InputSettingItem(
                        title = stringResource(R.string.protagonist_extraction_min_length),
                        value = settings.minLength.toString(),
                        defaultValue = ProtagonistExtractionSettings.DEFAULT.minLength.toString(),
                        onConfirm = { onIntent(ProtagonistExtractionConfigIntent.SetMinLength(it)) }
                    )
                    InputSettingItem(
                        title = stringResource(R.string.protagonist_extraction_max_length),
                        value = settings.maxLength.toString(),
                        defaultValue = ProtagonistExtractionSettings.DEFAULT.maxLength.toString(),
                        onConfirm = { onIntent(ProtagonistExtractionConfigIntent.SetMaxLength(it)) }
                    )
                    InputSettingItem(
                        title = stringResource(R.string.protagonist_extraction_invalid_words),
                        value = settings.invalidWords,
                        defaultValue = ProtagonistExtractionSettings.DEFAULT.invalidWords,
                        onConfirm = { onIntent(ProtagonistExtractionConfigIntent.SetInvalidWords(it)) }
                    )
                    SwitchSettingItem(
                        title = stringResource(R.string.protagonist_extraction_relaxed),
                        checked = settings.relaxedFirstLine,
                        onCheckedChange = { onIntent(ProtagonistExtractionConfigIntent.SetRelaxed(it)) }
                    )
                }
            }
        }
    }
}
