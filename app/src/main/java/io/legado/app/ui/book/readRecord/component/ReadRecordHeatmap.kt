package io.legado.app.ui.book.readRecord.component

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.fadingEdge
import io.legado.app.ui.widget.components.heatmap.*
import java.time.LocalDate

@Composable
fun HeatmapCalendarSection(
    modifier: Modifier = Modifier,
    dailyReadCounts: Map<LocalDate, Int>,
    dailyReadTimes: Map<LocalDate, Long>,
    currentMode: HeatmapMode,
    selectedDate: LocalDate?,
    onDateSelected: ((LocalDate) -> Unit)?,
    config: HeatmapConfig = HeatmapConfig(),
    showFadingEdge: Boolean = false,
    verticalPadding: Dp = 0.dp,
    legendSpacing: Dp = 8.dp
) {
    val (startDate, endDate) = rememberDateRange(dailyReadCounts, dailyReadTimes)
    val days = rememberDaysInRange(startDate, endDate)
    val weeks = rememberWeeks(days, startDate)

    val listState = rememberLazyListState()

    // 聚焦：滚动到 selectedDate 所在那一周并水平居中（reverseLayout=true，最早那周 index=0 在最右侧）
    LaunchedEffect(selectedDate, weeks) {
        if (weeks.isEmpty()) return@LaunchedEffect
        val focus = selectedDate
        val targetIndex = if (focus != null) {
            weeks.indexOfFirst { week -> week.any { it == focus } }
                .takeIf { it >= 0 }
                ?: (weeks.size - 1)
        } else {
            weeks.size - 1
        }
        listState.scrollToItem(targetIndex)
        // 把目标周中心对齐视口中心
        val layoutInfo = listState.layoutInfo
        val targetItem = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
            ?: return@LaunchedEffect
        val diff = (targetItem.offset + targetItem.size / 2f) - layoutInfo.viewportSize.width / 2f
        listState.scrollBy(diff)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (showFadingEdge) Modifier.fadingEdge(listState, config.gradientWidth)
                    else Modifier
                ),
            horizontalArrangement = Arrangement.spacedBy(config.cellSpacing),
            reverseLayout = true
        ) {
            items(weeks) { week ->
                HeatmapWeekColumn(
                    week = week,
                    mode = currentMode,
                    dailyReadCounts = dailyReadCounts,
                    dailyReadTimes = dailyReadTimes,
                    selectedDate = selectedDate,
                    config = config,
                    onDateSelected = onDateSelected
                )
            }
        }

        Spacer(modifier = Modifier.height(legendSpacing))

        HeatmapLegend(mode = currentMode, config = config)
    }
}
