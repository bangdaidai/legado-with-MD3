package io.legado.app.ui.config.protagonistExtractionConfig

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton

@Composable
fun ProtagonistExtractionConfigScreen(
    state: ProtagonistExtractionConfigUiState,
    onIntent: (ProtagonistExtractionConfigIntent) -> Unit,
    onBackClick: () -> Unit,
) {
    val settings = state.settings
    AppScaffold(
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.protagonist_extraction_config),
                navigationIcon = { TopBarNavigationButton(onClick = onBackClick) },
                actions = {
                    TextButton(onClick = { onIntent(ProtagonistExtractionConfigIntent.RestoreDefaults) }) {
                        Text(stringResource(R.string.restore_defaults))
                    }
                },
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .adaptiveContentPadding(
                    top = paddingValues.calculateTopPadding(),
                    bottom = 24.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConfigEditField(
                label = stringResource(R.string.protagonist_extraction_prefix),
                value = settings.protagonistPrefix,
                onValueChange = { onIntent(ProtagonistExtractionConfigIntent.SetProtagonistPrefix(it)) },
                singleLine = true,
            )
            ConfigEditField(
                label = stringResource(R.string.protagonist_extraction_supporting_prefix),
                value = settings.supportingPrefix,
                onValueChange = { onIntent(ProtagonistExtractionConfigIntent.SetSupportingPrefix(it)) },
                singleLine = true,
            )
            ConfigEditField(
                label = stringResource(R.string.protagonist_extraction_separators),
                value = settings.separators,
                onValueChange = { onIntent(ProtagonistExtractionConfigIntent.SetSeparators(it)) },
                singleLine = true,
            )
            ConfigEditField(
                label = stringResource(R.string.protagonist_extraction_min_length),
                value = settings.minLength.toString(),
                onValueChange = { onIntent(ProtagonistExtractionConfigIntent.SetMinLength(it)) },
                singleLine = true,
                keyboardType = KeyboardType.Number,
            )
            ConfigEditField(
                label = stringResource(R.string.protagonist_extraction_max_length),
                value = settings.maxLength.toString(),
                onValueChange = { onIntent(ProtagonistExtractionConfigIntent.SetMaxLength(it)) },
                singleLine = true,
                keyboardType = KeyboardType.Number,
            )
            ConfigEditField(
                label = stringResource(R.string.protagonist_extraction_invalid_words),
                value = settings.invalidWords,
                onValueChange = { onIntent(ProtagonistExtractionConfigIntent.SetInvalidWords(it)) },
                singleLine = false,
            )
            SwitchRow(
                label = stringResource(R.string.protagonist_extraction_relaxed),
                checked = settings.relaxedFirstLine,
                onCheckedChange = { onIntent(ProtagonistExtractionConfigIntent.SetRelaxed(it)) },
            )
        }
    }
}

@Composable
private fun ConfigEditField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            minLines = if (singleLine) 1 else 4,
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
