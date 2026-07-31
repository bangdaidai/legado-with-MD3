package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.hutool.core.date.DateUtil
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.data.entities.readRecord.ReadRecordTimelineDay
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.card.TextCard
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.utils.formatReadDuration
import java.util.Date

/**
 * 共用阅读时段时间轴组件。
 * 在 BookInfo 阅读记录 sheet 和 ReadingMemory 详情页中共用。
 *
 * @param parentIsScrollable 如果调用方已将此组件嵌套在外层可滚动容器中(如 LazyColumn/LazyListScope.item)，
 *                           应设为 true，此时内部不再使用 LazyColumn 避免嵌套滚动崩溃。
 */
@Composable
fun ReadingSessionTimeline(
    timelineDays: List<ReadRecordTimelineDay>,
    showChapterInfo: Boolean = true,
    modifier: Modifier = Modifier,
    parentIsScrollable: Boolean = false,
) {
    if (parentIsScrollable) {
        Column(modifier = modifier.fillMaxWidth()) {
            timelineDays.forEach { day ->
                AppText(
                    text = day.date,
                    style = LegadoTheme.typography.titleSmall,
                    color = LegadoTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                day.sessions.forEach { session ->
                    TimelineSessionRow(
                        session = session,
                        showChapterInfo = showChapterInfo,
                    )
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            timelineDays.forEach { day ->
                item(key = "header_${day.date}") {
                    AppText(
                        text = day.date,
                        style = LegadoTheme.typography.titleSmall,
                        color = LegadoTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                }
                items(day.sessions, key = { it.id }) { session ->
                    TimelineSessionRow(
                        session = session,
                        showChapterInfo = showChapterInfo,
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineSessionRow(
    session: ReadRecordSession,
    showChapterInfo: Boolean,
) {
    val lineColor = LegadoTheme.colorScheme.surfaceContainerHigh
    val nodeColor = LegadoTheme.colorScheme.primary
    val duration = (session.endTime - session.startTime).coerceAtLeast(0L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 12.dp.toPx()
                val centerY = size.height / 2f
                drawLine(
                    color = lineColor,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx()
                )
                drawCircle(
                    color = nodeColor,
                    radius = 4.dp.toPx(),
                    center = Offset(x, centerY)
                )
            }
            .padding(start = 28.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            AppText(
                text = DateUtil.format(Date(session.endTime), "HH:mm"),
                style = LegadoTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            AppText(
                text = formatReadDuration(duration),
                style = LegadoTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant
            )
        }
        if (showChapterInfo && session.words > 0) {
            TextCard(
                text = "第${session.words}章",
                textStyle = LegadoTheme.typography.labelSmall,
                backgroundColor = LegadoTheme.colorScheme.secondaryContainer,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}
