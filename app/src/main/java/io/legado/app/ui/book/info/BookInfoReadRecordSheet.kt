package io.legado.app.ui.book.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import io.legado.app.R
import io.legado.app.data.entities.readRecord.ReadRecordTimelineDay
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.EmptyMessage
import io.legado.app.ui.widget.components.ReadingSessionTimeline
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.utils.formatReadDuration

@Composable
fun BookReadRecordSheet(
    show: Boolean,
    totalReadTime: Long,
    timelineDays: List<ReadRecordTimelineDay>,
    onDismissRequest: () -> Unit,
) {
    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.read_record),
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            containerColor = LegadoTheme.colorScheme.surfaceContainerLow
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Timeline,
                    contentDescription = null,
                    tint = LegadoTheme.colorScheme.primary
                )
                Column {
                    AppText(
                        text = stringResource(R.string.all_read_time),
                        style = LegadoTheme.typography.labelMedium,
                        color = LegadoTheme.colorScheme.primary
                    )
                    AppText(
                        text = formatReadDuration(totalReadTime),
                        style = LegadoTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (timelineDays.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                EmptyMessage(message = stringResource(R.string.empty))
            }
        } else {
            ReadingSessionTimeline(
                timelineDays = timelineDays,
                showChapterInfo = true,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
