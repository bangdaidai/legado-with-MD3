package io.legado.app.data.entities

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 今日书讯的章数换算、同日合并与本地日换算边界。 */
class BookUpdateTest {

    private fun record(
        epochDay: Long = 1L,
        total: Int = 105,
        baseline: Int? = 100,
        bookUrl: String = "http://example.com/book/1",
        origin: String = "http://example.com/source",
    ) = BookUpdate(
        epochDay = epochDay,
        bookUrl = bookUrl,
        origin = origin,
        latestChapterTitle = null,
        foundAt = 1000L,
        totalChapterNum = total,
        baselineChapterNum = baseline,
    )

    @Test
    fun chapterCountSubtractsBaseline() {
        assertEquals(15, bookUpdateChapterCount(90, 105))
    }

    @Test
    fun unknownOrZeroBaselineStaysNull() {
        // 基线未知绝不冒充已知：展示层会回落为"已更新"
        assertNull(bookUpdateChapterCount(null, 105))
        assertNull(bookUpdateChapterCount(0, 105))
    }

    @Test
    fun noGrowthStaysNull() {
        assertNull(bookUpdateChapterCount(105, 105))
        assertNull(bookUpdateChapterCount(110, 105))
    }

    @Test
    fun firstRecordOfTheDayIsTakenAsIs() {
        assertEquals(record(), mergeBookUpdateIfNewer(null, record()))
    }

    @Test
    fun sameDayHigherTotalKeepsFirstBaseline() {
        val merged = mergeBookUpdateIfNewer(
            record(total = 105, baseline = 100),
            record(total = 108, baseline = 105),
        )
        assertEquals(108, merged?.totalChapterNum)
        // 保留当日首个基线：多次刷新不把"更新 8 章"滚成累计差值
        assertEquals(100, merged?.baselineChapterNum)
    }

    @Test
    fun sameDayLowerOrEqualTotalIsIgnored() {
        val previous = record(total = 108, baseline = 100)
        assertNull(mergeBookUpdateIfNewer(previous, record(total = 105, baseline = 105)))
        assertNull(mergeBookUpdateIfNewer(previous, record(total = 108, baseline = 105)))
    }

    @Test
    fun differentDayOrBookOrOriginIsIgnored() {
        val previous = record(epochDay = 1, total = 105, baseline = 100)
        assertNull(mergeBookUpdateIfNewer(previous, record(epochDay = 2, total = 110)))
        assertNull(mergeBookUpdateIfNewer(previous, record(bookUrl = "http://example.com/book/2")))
        assertNull(mergeBookUpdateIfNewer(previous, record(origin = "http://example.com/source2")))
    }

    @Test
    fun epochDayFollowsTheGivenZone() {
        // 1704126600000 = 2024-01-01 16:30 UTC = 2024-01-02 00:30 (UTC+8)
        val foundAt = 1704126600000L
        assertEquals(19723L, bookUpdateEpochDay(foundAt, TimeZone.UTC))
        assertEquals(19724L, bookUpdateEpochDay(foundAt, TimeZone.of("Asia/Shanghai")))
    }
}
