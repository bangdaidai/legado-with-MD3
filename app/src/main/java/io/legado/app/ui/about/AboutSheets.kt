package io.legado.app.ui.about

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
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
fun MarkdownSheet(
    show: Boolean,
    title: String,
    content: String,
    onDismissRequest: () -> Unit,
    endAction: @Composable (() -> Unit)? = null,
) {
    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = title,
        endAction = endAction,
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                MarkdownBlock(
                    content = content,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.heightIn(min = 16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(
    show: Boolean,
    updateInfo: AppUpdate.UpdateInfo,
    mode: UpdateMode,
    onDismissRequest: () -> Unit,
    onStartDownload: () -> Unit,
) {
    val title = when (mode) {
        UpdateMode.UPDATE -> stringResource(R.string.check_update)
        UpdateMode.VIEW_LOG -> stringResource(R.string.about_installed_version_title)
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = title,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            if (mode == UpdateMode.UPDATE) {
                AppText(
                    text = stringResource(R.string.about_current_version) + " " + BuildConfig.VERSION_NAME,
                    style = LegadoTheme.typography.titleSmall,
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
                    style = LegadoTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = LegadoTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val downloadUrl = updateInfo.downloadUrl
                if (downloadUrl.isNotBlank()) {
                    AppText(
                        text = downloadUrl,
                        style = LegadoTheme.typography.bodySmall,
                        color = LegadoTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            } else {
                AppText(
                    text = BuildConfig.VERSION_NAME,
                    style = LegadoTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = LegadoTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            AppText(
                text = stringResource(R.string.update_log),
                style = LegadoTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp),
            )

            val updateLog = updateInfo.updateLog
            if (updateLog.isNotBlank()) {
                MarkdownBlock(
                    content = updateLog,
                    style = LegadoTheme.typography.bodySmall,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                )
            }

            if (mode == UpdateMode.UPDATE) {
                Spacer(modifier = Modifier.height(16.dp))
                PrimaryButton(
                    onClick = onStartDownload,
                    text = stringResource(R.string.about_update_action),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
