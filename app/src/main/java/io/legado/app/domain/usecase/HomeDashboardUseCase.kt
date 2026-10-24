package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.HomeDashboardGateway
import io.legado.app.data.repository.BookUpdateRepository
import io.legado.app.domain.model.DEFAULT_DAILY_READING_GOAL_MINUTES
import io.legado.app.domain.model.HomeBookNewsBook
import io.legado.app.domain.model.HomeDashboard
import io.legado.app.domain.model.HomeDashboardSection
import io.legado.app.domain.model.HomepageLayoutMode
import io.legado.app.domain.model.MAX_DAILY_READING_GOAL_MINUTES
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

class HomeDashboardUseCase(
    private val gateway: HomeDashboardGateway,
    private val bookUpdateRepository: BookUpdateRepository,
    private val clock: Clock,
) {

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(): Flow<HomeDashboard> {
        val todayReadTime = currentDate()
            .flatMapLatest(gateway::observeReadTime)

        return combine(
            gateway.observeTotalReadBooks(),
            gateway.observeTotalReadTime(),
            todayReadTime,
            gateway.observeRecentBooks(RECENT_BOOK_LIMIT),
            gateway.observeDailyGoal(DEFAULT_DAILY_READING_GOAL_MINUTES),
        ) { totalBooks, totalTime, todayTime, recentBooks, dailyGoal ->
            HomeDashboard(
                totalReadBooks = totalBooks,
                totalReadTimeMillis = totalTime,
                todayReadTimeMillis = todayTime,
                dailyGoalMinutes = dailyGoal.coerceIn(
                    1,
                    MAX_DAILY_READING_GOAL_MINUTES,
                ),
                recentBooks = recentBooks,
            )
        }
    }

    suspend fun updateDailyGoal(minutes: Int) {
        gateway.updateDailyGoal(
            minutes.coerceIn(1, MAX_DAILY_READING_GOAL_MINUTES)
        )
    }

    fun observeSelectedSourceSetUrl(): Flow<String?> =
        gateway.observeSelectedSourceSetUrl()

    fun observeVisibleSections(): Flow<Set<HomeDashboardSection>> =
        gateway.observeVisibleSections()

    fun observeLayoutMode(): Flow<HomepageLayoutMode> =
        gateway.observeLayoutMode().map { HomepageLayoutMode.fromStorage(it) }

    suspend fun updateSelectedSourceSetUrl(sourceUrl: String) {
        gateway.updateSelectedSourceSetUrl(sourceUrl)
    }

    suspend fun updateLayoutMode(mode: HomepageLayoutMode) {
        gateway.updateLayoutMode(mode.storageValue)
    }

    suspend fun updateVisibleSections(sections: Set<HomeDashboardSection>) {
        gateway.updateVisibleSections(sections)
    }

    /** 今日书讯：跨零点自动换日重查，books 表频繁变化靠 distinctUntilChanged 去抖 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeBookNews(): Flow<List<HomeBookNewsBook>> = currentEpochDay()
        .flatMapLatest(bookUpdateRepository::observeBooksForDay)
        .distinctUntilChanged()

    private fun currentEpochDay(): Flow<Long> = flow {
        while (true) {
            val now = clock.now()
            val zone = TimeZone.currentSystemDefault()
            val today = now.toLocalDateTime(zone).date
            emit(today.toEpochDays())
            val nextDay = today
                .plus(DatePeriod(days = 1))
                .atStartOfDayIn(zone)
            delay(
                (nextDay - now).inWholeMilliseconds
                    .coerceAtLeast(MIN_DATE_REFRESH_DELAY_MILLIS)
            )
        }
    }.distinctUntilChanged()

    private fun currentDate(): Flow<String> = flow {
        while (true) {
            val now = clock.now()
            val zone = TimeZone.currentSystemDefault()
            emit(now.toLocalDateTime(zone).date.toString())
            val nextDay = now.toLocalDateTime(zone).date
                .plus(DatePeriod(days = 1))
                .atStartOfDayIn(zone)
            delay(
                (nextDay - now).inWholeMilliseconds
                    .coerceAtLeast(MIN_DATE_REFRESH_DELAY_MILLIS)
            )
        }
    }.distinctUntilChanged()

    private companion object {
        const val RECENT_BOOK_LIMIT = 7
        const val MIN_DATE_REFRESH_DELAY_MILLIS = 1_000L
    }
}
