package io.legado.app.ui.book.readRecord

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.ui.book.readRecord.component.ReadingBar
import io.legado.app.ui.book.readRecord.component.ReadingTimeBarChartCard
import io.legado.app.ui.book.readRecord.component.StatItem
import io.legado.app.ui.book.readRecord.component.StatsGridCard
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveHorizontalPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.button.series.MediumPlainButton
import io.legado.app.ui.widget.components.card.GlassCard
import io.legado.app.ui.widget.components.heatmap.HeatmapMode
import io.legado.app.ui.widget.components.image.cover.CoilBookCover
import io.legado.app.ui.widget.components.tabRow.AppTabRow
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import org.koin.androidx.compose.koinViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadRecordOverviewRouteScreen(
    viewModel: ReadRecordOverviewViewModel = koinViewModel(),
    onBackClick: () -> Unit,
    onBookClick: (String, String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ReadRecordOverviewScreen(
        state = state,
        onIntent = viewModel::onIntent,
        loadBookCover = viewModel::getBookCover,
        onBackClick = onBackClick,
        onBookClick = onBookClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadRecordOverviewScreen(
    state: ReadRecordOverviewUiState,
    onIntent: (ReadRecordOverviewIntent) -> Unit,
    loadBookCover: suspend (String, String) -> String?,
    onBackClick: () -> Unit,
    onBookClick: (String, String) -> Unit,
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.read_record_overview),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBackClick)
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PeriodSelector(
                selectedPeriod = state.period,
                onPeriodSelected = { onIntent(ReadRecordOverviewIntent.SetPeriod(it)) }
            )

            DateNavigator(
                period = state.period,
                referenceDate = state.referenceDate,
                onPrevClick = { onIntent(ReadRecordOverviewIntent.PreviousDate) },
                onNextClick = { onIntent(ReadRecordOverviewIntent.NextDate) }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {

                item {
                    val readingTime = ReadRecordFormatter.hourMinuteDuration(state.totalTime)
                    val readingTimeText = when {
                        readingTime.hours > 0 && readingTime.minutes > 0 -> stringResource(
                            R.string.hours_minutes_format,
                            readingTime.hours,
                            readingTime.minutes,
                        )
                        readingTime.hours > 0 -> stringResource(
                            R.string.whole_hours_format,
                            readingTime.hours,
                        )
                        else -> stringResource(
                            R.string.minutes_format,
                            readingTime.minutes,
                        )
                    }
                    val stats = listOf(
                        StatItem(stringResource(R.string.reading_time), readingTimeText),
                        StatItem(stringResource(R.string.stat_type_text), stringResource(R.string.books_format, state.textBookCount)),
                        StatItem(stringResource(R.string.stat_type_video), stringResource(R.string.parts_format, state.videoBookCount)),
                        StatItem(stringResource(R.string.stat_type_audio), stringResource(R.string.parts_format, state.audioBookCount)),
                        StatItem(stringResource(R.string.reading_books), stringResource(R.string.books_format, state.readingBooks)),
                        StatItem(stringResource(R.string.finished_books), stringResource(R.string.books_format, state.finishedBooks)),
                        StatItem(stringResource(R.string.abandoned_books), stringResource(R.string.books_format, state.abandonedBooks)),
                        StatItem(stringResource(R.string.reading_days), stringResource(R.string.days_format, state.readingDays)),
                        StatItem(stringResource(R.string.review_count), stringResource(R.string.entries_format, state.reviewCount)),
                        StatItem(stringResource(R.string.reading_words), ReadRecordFormatter.formatWords(state.totalWords))
                    )
                    StatsGridCard(title = stringResource(R.string.reading_data), items = stats)
                }

                if (state.period != ReadPeriod.ALL) {
                    item {
                        ReadingDistributionCard(state)
                    }
                }


                item {
                    HeatmapCard(state)
                }

                if (state.topBooks.isNotEmpty()) {
                    item {
                        TopReadingListCard(state.topBooks, loadBookCover, onBookClick)
                    }
                }

                if (state.period == ReadPeriod.MONTH) {
                    item {
                        ReadingCalendarCard(state, loadBookCover)
                    }
                }
            }
        }
    }
}

@Composable
fun PeriodSelector(
    selectedPeriod: ReadPeriod,
    onPeriodSelected: (ReadPeriod) -> Unit
) {
    val periods = remember {
        listOf(
            ReadPeriod.DAY to "日",
            ReadPeriod.WEEK to "周",
            ReadPeriod.MONTH to "月",
            ReadPeriod.YEAR to "年",
            ReadPeriod.ALL to "总"
        )
    }

    AppTabRow(
        tabTitles = periods.map { it.second },
        selectedTabIndex = periods.indexOfFirst { it.first == selectedPeriod },
        onTabSelected = { index -> onPeriodSelected(periods[index].first) },
        isScrollable = false
    )
}

@Composable
fun DateNavigator(
    period: ReadPeriod,
    referenceDate: LocalDate,
    onPrevClick: () -> Unit,
    onNextClick: () -> Unit
) {
    if (period == ReadPeriod.ALL) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        MediumPlainButton(
            onClick = onPrevClick,
            icon = Icons.AutoMirrored.Filled.ArrowLeft,
            contentDescription = stringResource(R.string.previous)
        )
        AnimatedContent(
            targetState = referenceDate,
            transitionSpec = {
                if (targetState.isAfter(initialState)) {
                    (slideInHorizontally { it / 2 } + fadeIn()).togetherWith(slideOutHorizontally { -it / 2 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 2 } + fadeIn()).togetherWith(slideOutHorizontally { it / 2 } + fadeOut())
                }.using(SizeTransform(clip = false))
            },
            label = "DateNavigator"
        ) { targetDate ->
            val text = when (period) {
                ReadPeriod.DAY -> targetDate.format(DateTimeFormatter.ofPattern("yyyy年M月d日"))
                ReadPeriod.WEEK -> {
                    val start = targetDate.with(java.time.DayOfWeek.MONDAY)
                    val end = targetDate.with(java.time.DayOfWeek.SUNDAY)
                    "${start.format(DateTimeFormatter.ofPattern("M.d"))} - ${
                        end.format(
                            DateTimeFormatter.ofPattern("M.d")
                        )
                    }"
                }

                ReadPeriod.MONTH -> targetDate.format(DateTimeFormatter.ofPattern("yyyy年M月"))
                ReadPeriod.YEAR -> targetDate.format(DateTimeFormatter.ofPattern("yyyy年"))
                ReadPeriod.ALL -> ""
            }
            AppText(
                text = text,
                style = LegadoTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        MediumPlainButton(
            onClick = onNextClick,
            icon = Icons.AutoMirrored.Filled.ArrowRight,
            contentDescription = stringResource(R.string.next)
        )
    }
}

@Composable
fun ReadingDistributionCard(state: ReadRecordOverviewUiState) {
    when (state.period) {
        ReadPeriod.DAY -> {
            if (state.hourlyTimeData.isEmpty()) return
            val bars = state.hourlyTimeData.map { (hour, time) ->
                // 每 6 小时标一次: 0 / 6 / 12 / 18；最后一根柱(23)的右侧标为"24"表示当日结束
                val label = when {
                    hour == 23 -> "24"
                    hour % 6 == 0 -> hour.toString()
                    else -> null
                }
                ReadingBar(label = label, value = time)
            }
            ReadingTimeBarChartCard(
                bars = bars,
                title = stringResource(R.string.reading_hourly_distribution),
                barWidthFraction = 0.7f
            )
        }

        ReadPeriod.WEEK -> {
            if (state.dailyTimeData.isEmpty()) return
            val bars = state.dailyTimeData.map { (date, time) ->
                val label = when (date.dayOfWeek.value) {
                    1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"; 6 -> "六"; 7 -> "日"; else -> ""
                }
                ReadingBar(label = label, value = time)
            }
            ReadingTimeBarChartCard(
                bars = bars,
                title = stringResource(R.string.reading_time_distribution),
                barWidthFraction = 0.6f
            )
        }

        ReadPeriod.MONTH -> {
            if (state.dailyTimeData.isEmpty()) return
            val lastIndex = state.dailyTimeData.lastIndex
            val bars = state.dailyTimeData.mapIndexed { index, (date, time) ->
                val show = date.dayOfMonth == 1 || date.dayOfMonth == 15 || index == lastIndex
                ReadingBar(label = if (show) date.dayOfMonth.toString() else null, value = time)
            }
            ReadingTimeBarChartCard(
                bars = bars,
                title = stringResource(R.string.reading_time_distribution),
                barWidthFraction = 0.8f
            )
        }

        ReadPeriod.YEAR -> {
            if (state.dailyTimeData.isEmpty()) return
            val bars = state.dailyTimeData.map { (date, time) ->
                ReadingBar(label = "${date.monthValue}月", value = time)
            }
            ReadingTimeBarChartCard(
                bars = bars,
                title = stringResource(R.string.reading_time_distribution),
                barWidthFraction = 0.6f
            )
        }

        ReadPeriod.ALL -> Unit
    }
}

@Composable
fun HeatmapCard(state: ReadRecordOverviewUiState) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveHorizontalPadding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.GridOn,
                    contentDescription = null,
                    tint = LegadoTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                AppText(stringResource(R.string.reading_heatmap), style = LegadoTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(8.dp))
            HeatmapCalendarSection(
                dailyReadCounts = state.allReadCounts,
                dailyReadTimes = state.allReadTimes,
                currentMode = HeatmapMode.TIME,
                selectedDate = state.referenceDate,
                onDateSelected = null
            )
        }
    }
}

@Composable
fun TopReadingListCard(
    topBooks: List<ReadBookRanking>,
    loadBookCover: suspend (String, String) -> String?,
    onBookClick: (String, String) -> Unit
) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveHorizontalPadding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Leaderboard,
                    contentDescription = null,
                    tint = LegadoTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                AppText(
                    text = stringResource(R.string.reading_time_ranking),
                    style = LegadoTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            topBooks.forEachIndexed { index, book ->
                var coverPath by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(book.bookName, book.bookAuthor) {
                    coverPath = loadBookCover(book.bookName, book.bookAuthor)
                }
                val rankingDescription = stringResource(
                    R.string.a11y_reading_ranking_item,
                    index + 1,
                    book.bookName,
                    book.bookAuthor,
                    ReadRecordFormatter.formatDuration(book.readTime)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onBookClick(book.bookName, book.bookAuthor) }
                        .semantics(mergeDescendants = true) {
                            contentDescription = rankingDescription
                            role = Role.Button
                        }
                        .padding(vertical = 8.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AppText(
                        text = "${index + 1}",
                        style = LegadoTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 12.dp, end = 16.dp),
                        textAlign = TextAlign.Center,
                        color = if (index < 3) LegadoTheme.colorScheme.primary else LegadoTheme.colorScheme.onSurfaceVariant
                    )
                    CoilBookCover(
                        name = book.bookName,
                        author = book.bookAuthor,
                        path = coverPath,
                        modifier = Modifier.width(40.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        AppText(
                            modifier = Modifier.padding(end = 8.dp),
                            text = ReadRecordFormatter.formatDuration(book.readTime),
                            style = LegadoTheme.typography.bodySmall,
                            color = LegadoTheme.colorScheme.primary
                        )
                        AppText(
                            text = book.bookName,
                            style = LegadoTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        AppText(
                            text = book.bookAuthor,
                            style = LegadoTheme.typography.labelSmall,
                            color = LegadoTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ReadingCalendarCard(
    state: ReadRecordOverviewUiState,
    loadBookCover: suspend (String, String) -> String?,
) {
    val currentMonth = YearMonth.from(state.referenceDate)
    val daysInMonth = currentMonth.lengthOfMonth()
    val firstDayOfMonth = currentMonth.atDay(1)
    // 周一为第一天: Monday=0, Tuesday=1, ..., Sunday=6
    val firstDayOfWeek = (firstDayOfMonth.dayOfWeek.value - 1) % 7

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .adaptiveHorizontalPadding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    tint = LegadoTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                AppText(stringResource(R.string.reading_calendar), style = LegadoTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth()) {
                val days = listOf("一", "二", "三", "四", "五", "六", "日")
                days.forEach { day ->
                    AppText(
                        text = day,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = LegadoTheme.typography.labelSmall
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            val totalCells = ((daysInMonth + firstDayOfWeek + 6) / 7) * 7
            for (i in 0 until totalCells step 7) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (j in 0 until 7) {
                        val cellIndex = i + j
                        val dayOfMonth = cellIndex - firstDayOfWeek + 1
                        
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(0.75f)
                                .padding(2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (dayOfMonth in 1..daysInMonth) {
                                val date = currentMonth.atDay(dayOfMonth)
                                CalendarDayCell(date, state, loadBookCover)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarDayCell(
    date: LocalDate,
    state: ReadRecordOverviewUiState,
    loadBookCover: suspend (String, String) -> String?,
) {
    val topBook = state.dailyTopBook[date]
    var coverPath by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(topBook) {
        topBook?.let { (name, author) ->
            coverPath = loadBookCover(name, author)
        }
    }
    val dayDescription = if (topBook != null) {
        stringResource(
            R.string.a11y_reading_calendar_day_with_book,
            date.toString(),
            topBook.first,
            topBook.second
        )
    } else {
        stringResource(R.string.a11y_reading_calendar_day_empty, date.toString())
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
            .background(LegadoTheme.colorScheme.surfaceVariant)
            .semantics {
                contentDescription = dayDescription
            },
        contentAlignment = Alignment.Center
    ) {
        if (topBook != null) {
            CoilBookCover(
                name = topBook.first,
                author = topBook.second,
                path = coverPath,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.5f)
            )
        }
        
        AppText(
            text = date.dayOfMonth.toString(),
            style = LegadoTheme.typography.bodySmall,
            fontWeight = if (topBook != null) FontWeight.Bold else FontWeight.Normal,
            color = if (topBook != null) LegadoTheme.colorScheme.primary else LegadoTheme.colorScheme.onSurface
        )
    }
}
