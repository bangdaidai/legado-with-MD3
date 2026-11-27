package io.legado.app.ui.about

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.help.update.AppUpdate
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.button.PrimaryButton
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.text.MarkdownBlock

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
    show: Boolean,
    updateInfo: AppUpdate.UpdateInfo,
    mode: UpdateMode,
    updateToVariant: String,
    onDismissRequest: () -> Unit,
    onStartDownload: () -> Unit,
) {
    val title = stringResource(R.string.update_log)

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = title,
        animateContentSize = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (mode == UpdateMode.UPDATE) {
                Row(
                    modifier = Modifier.padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText(
                        text = stringResource(R.string.about_current_version),
                        style = LegadoTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    AppText(
                        text = BuildConfig.VERSION_NAME,
                        style = LegadoTheme.typography.bodyMedium,
                        color = LegadoTheme.colorScheme.outline,
                    )
                }
                Row(
                    modifier = Modifier.padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText(
                        text = stringResource(R.string.about_new_version),
                        style = LegadoTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    AppText(
                        text = updateInfo.tagName,
                        style = LegadoTheme.typography.bodyMedium,
                        color = LegadoTheme.colorScheme.primary,
                    )
                }
                Row(
                    modifier = Modifier.padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText(
                        text = "ABI",
                        style = LegadoTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    AppText(
                        text = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
                        style = LegadoTheme.typography.bodyMedium,
                        color = LegadoTheme.colorScheme.outline,
                    )
                }
                Row(
                    modifier = Modifier.padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText(
                        text = stringResource(R.string.about_update_channel),
                        style = LegadoTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    AppText(
                        text = updateToVariant,
                        style = LegadoTheme.typography.bodyMedium,
                        color = LegadoTheme.colorScheme.outline,
                    )
                }
            } else {
                AppText(
                    text = stringResource(R.string.about_current_version) + " " + BuildConfig.VERSION_NAME,
                    style = LegadoTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                // 版本分隔线
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(2.dp)
                        .background(LegadoTheme.colorScheme.primaryContainer)
                )
                AppText(
                    text = stringResource(R.string.about_new_version) + " " + updateInfo.tagName,
                    style = LegadoTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = LegadoTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val downloadUrl = updateInfo.downloadUrl
                if (downloadUrl.isNotBlank()) {
                    AppText(
                        text = downloadUrl,
                        style = LegadoTheme.typography.labelLarge,
                        color = LegadoTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            val updateLog = updateInfo.updateLog
                .replaceFirst(Regex("^#\\s*更新日志\\s*\\n?"), "")
                .trim()
            if (updateLog.isNotBlank()) {
                SelectionContainer {
                    MarkdownBlock(
                        content = updateLog,
                        style = LegadoTheme.typography.labelLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                }
            }

            if (mode == UpdateMode.UPDATE) {
                Spacer(modifier = Modifier.height(16.dp))
                PrimaryButton(
                    onClick = onStartDownload,
                    text = stringResource(R.string.about_update_action),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (mode == UpdateMode.UPDATE) {
            Spacer(modifier = Modifier.height(16.dp))
            PrimaryButton(
                onClick = onStartDownload,
                text = stringResource(R.string.about_update_action),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
