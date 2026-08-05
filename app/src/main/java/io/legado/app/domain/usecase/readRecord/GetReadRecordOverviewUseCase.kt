package io.legado.app.domain.usecase.readRecord

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.ui.book.readRecord.ReadBookRanking
import io.legado.app.ui.book.readRecord.ReadPeriod
import io.legado.app.ui.book.readRecord.ReadRecordOverviewUiState
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

class GetReadRecordOverviewUseCase {

    operator fun invoke(
        period: ReadPeriod,
        refDate: LocalDate,
        details: List<ReadRecordDetail>,
        latestRecords: List<ReadRecord>,
        allBooks: List<Book>,
        sessions: List<ReadRecordSession>
    ): ReadRecordOverviewUiState {
        val (startDate, endDate) = getPeriodRange(period, refDate)

        val filteredDetails = if (period == ReadPeriod.ALL) {
            details
        } else {
            details.filter {
                try {
                    val d = LocalDate.parse(it.date, DateTimeFormatter.ISO_LOCAL_DATE)
                    !d.isBefore(startDate) && !d.isAfter(endDate)
                } catch (e: Exception) {
                    false
                }
            }
        }

        val totalTime = filteredDetails.sumOf { it.readTime }
        val totalWords = filteredDetails.sumOf { it.readWords }
        val readingDays = filteredDetails.map { it.date }.distinct().size

        val periodBooks = filteredDetails.groupBy { it.bookName to it.bookAuthor }
        val totalBooks = periodBooks.size

        val shelfBooksMap = latestRecords.associateBy { it.bookName to it.bookAuthor }
        val allShelfBooksMap = allBooks.associateBy { it.name to it.author }
        
        var readingCount = 0
        var finishedCount = 0
        
        periodBooks.keys.forEach { key ->
            if (shelfBooksMap.containsKey(key)) {
                readingCount++
            }
            // 判断是否读完
            allShelfBooksMap[key]?.let { book ->
                if (book.totalChapterNum > 0 &&
                    book.durChapterIndex >= book.totalChapterNum - 1 &&
                    book.durChapterPos != 0
                ) {
                    finishedCount++
                }
            }
        }

        val topBooks = periodBooks.map { (key, details) ->
            ReadBookRanking(
                bookName = key.first,
                bookAuthor = key.second,
                readTime = details.sumOf { it.readTime }
            )
        }.sortedByDescending { it.readTime }.take(10)

        val dailyTopBookMap = details.groupBy { it.date }
            .mapNotNull { (dateStr, dayDetails) ->
                try {
                    val date = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE)
                    val top = dayDetails.maxByOrNull { it.readTime }
                    if (top != null) date to (top.bookName to top.bookAuthor) else null
                } catch (e: Exception) {
                    null
                }
            }.toMap()

        val dailyTimeData = if (period == ReadPeriod.ALL) {
            emptyList()
        } else if (period == ReadPeriod.YEAR) {
            val dateToTime = filteredDetails.groupBy { 
                LocalDate.parse(it.date, DateTimeFormatter.ISO_LOCAL_DATE).with(TemporalAdjusters.firstDayOfMonth())
            }.mapValues { it.value.sumOf { d -> d.readTime } }
            
            val monthList = mutableListOf<Pair<LocalDate, Long>>()
            var curr = startDate.with(TemporalAdjusters.firstDayOfMonth())
            while (!curr.isAfter(endDate)) {
                monthList.add(curr to (dateToTime[curr] ?: 0L))
                curr = curr.plusMonths(1)
            }
            monthList
        } else {
            val dateToTime = filteredDetails.groupBy { it.date }
                .mapValues { it.value.sumOf { d -> d.readTime } }

            val daysList = mutableListOf<Pair<LocalDate, Long>>()
            var curr = startDate
            while (!curr.isAfter(endDate)) {
                daysList.add(curr to (dateToTime[curr.format(DateTimeFormatter.ISO_LOCAL_DATE)] ?: 0L))
                curr = curr.plusDays(1)
            }
            daysList
        }

        val allReadTimesMap = details.groupBy { it.date }
            .mapKeys {
                try {
                    LocalDate.parse(it.key, DateTimeFormatter.ISO_LOCAL_DATE)
                } catch (e: Exception) {
                    LocalDate.MIN
                }
            }
            .filterKeys { it != LocalDate.MIN }
            .mapValues { it.value.sumOf { d -> d.readTime } }

        val allReadCountsMap = details.groupBy { it.date }
            .mapKeys {
                try {
                    LocalDate.parse(it.key, DateTimeFormatter.ISO_LOCAL_DATE)
                } catch (e: Exception) {
                    LocalDate.MIN
                }
            }
            .filterKeys { it != LocalDate.MIN }
            .mapValues { it.value.size }

        val hourlyTimeData = if (period == ReadPeriod.DAY) {
            computeHourlyDistribution(refDate, sessions)
        } else {
            emptyList()
        }

        return ReadRecordOverviewUiState(
            period = period,
            referenceDate = refDate,
            totalTime = totalTime,
            readingDays = readingDays,
            totalBooks = totalBooks,
            finishedBooks = finishedCount,
            readingBooks = readingCount,
            totalWords = totalWords,
            dailyTimeData = dailyTimeData,
            hourlyTimeData = hourlyTimeData,
            topBooks = topBooks,
            dailyTopBook = dailyTopBookMap,
            allReadTimes = allReadTimesMap,
            allReadCounts = allReadCountsMap
        )
    }

    /**
     * 将某一天内的阅读会话按小时(0-23)拆分累加，返回 24 个桶的时长(毫秒)。
     * 跨小时的会话会按各小时占用的时间分别计入对应桶。
     */
    private fun computeHourlyDistribution(
        refDate: LocalDate,
        sessions: List<ReadRecordSession>
    ): List<Pair<Int, Long>> {
        val zone = ZoneId.systemDefault()
        val dayStart = refDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = refDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val buckets = LongArray(24)
        sessions.forEach { session ->
            val start = maxOf(session.startTime, dayStart)
            val end = minOf(session.endTime, dayEnd)
            if (end <= start) return@forEach
            var cursor = start
            while (cursor < end) {
                val hour = ((cursor - dayStart) / 3_600_000L).toInt().coerceIn(0, 23)
                val nextHourBoundary = dayStart + (hour + 1) * 3_600_000L
                val segmentEnd = minOf(end, nextHourBoundary)
                buckets[hour] += segmentEnd - cursor
                cursor = segmentEnd
            }
        }
        return (0..23).map { it to buckets[it] }
    }

    private fun getPeriodRange(period: ReadPeriod, refDate: LocalDate): Pair<LocalDate, LocalDate> {
        return when (period) {
            ReadPeriod.DAY -> refDate to refDate
            ReadPeriod.WEEK -> {
                val start = refDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val end = refDate.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                start to end
            }
            ReadPeriod.MONTH -> {
                val start = refDate.with(TemporalAdjusters.firstDayOfMonth())
                val end = refDate.with(TemporalAdjusters.lastDayOfMonth())
                start to end
            }
            ReadPeriod.YEAR -> {
                val start = refDate.with(TemporalAdjusters.firstDayOfYear())
                val end = refDate.with(TemporalAdjusters.lastDayOfYear())
                start to end
            }
            ReadPeriod.ALL -> LocalDate.MIN to LocalDate.MAX
        }
    }
}
