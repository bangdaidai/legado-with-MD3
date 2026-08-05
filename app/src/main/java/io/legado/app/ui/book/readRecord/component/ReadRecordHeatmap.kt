package io.legado.app.ui.book.readRecord.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    config: HeatmapConfig = HeatmapConfig()
) {
    val (startDate, endDate) = rememberDateRange(dailyReadCounts, dailyReadTimes)
    val days = rememberDaysInRange(startDate, endDate)
    val weeks = rememberWeeks(days, startDate)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
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

        Spacer(modifier = Modifier.height(8.dp))

        HeatmapLegend(mode = currentMode, config = config)
    }
}
