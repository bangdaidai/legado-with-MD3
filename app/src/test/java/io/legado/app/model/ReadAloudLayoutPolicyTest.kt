package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudLayoutPolicyTest {

    @Test
    fun sameChapterRelayoutKeepsCurrentPlaybackPosition() {
        assertFalse(
            shouldRestartReadAloudAfterContentLoad(
                preserveReadAloudPosition = true,
                serviceChapterIndex = 4,
                loadedChapterIndex = 4,
            )
        )
    }

    @Test
    fun userPositionChangeRestartsPlayback() {
        assertTrue(
            shouldRestartReadAloudAfterContentLoad(
                preserveReadAloudPosition = false,
                serviceChapterIndex = 4,
                loadedChapterIndex = 4,
            )
        )
    }

    @Test
    fun nextChapterLoadStartsPendingChapter() {
        assertTrue(
            shouldRestartReadAloudAfterContentLoad(
                preserveReadAloudPosition = true,
                serviceChapterIndex = 4,
                loadedChapterIndex = 5,
            )
        )
    }

    @Test
    fun activePlaybackRestoresLatestServiceProgress() {
        assertEquals(
            128,
            activeReadAloudProgress(
                isPlaying = true,
                currentProgress = 128,
            )
        )
    }

    @Test
    fun inactivePlaybackDoesNotRestoreStaleProgress() {
        assertNull(
            activeReadAloudProgress(
                isPlaying = false,
                currentProgress = 128,
            )
        )
    }

    // ── 听书页返回阅读页后「页面追声音」接管（同章声音领先、批次落地）──

    @Test
    fun speechAheadOfFrozenPagePullsPageInsteadOfRestartRound() {
        // 复现：听书页盖住阅读页期间翻页失败，页面冻在第3页页首(200)，声音已读到(500)；
        // 旧路径此刻会 readAloud() 从页首重起轮＝声音被拽回 200 重读。
        assertTrue(
            shouldPullPageToSpeechPosition(
                preserveReadAloudPosition = false,
                scrollMode = false,
                serviceChapterIndex = 4,
                serviceChapterPos = 500,
                loadedChapterIndex = 4,
                pageChapterPos = 200,
            )
        )
    }

    @Test
    fun pageAheadOfSpeechStillRestartsFromPage() {
        // 用户把页面跳到声音之后（页面领先）：维持「声音跟页」，不接管。
        assertFalse(
            shouldPullPageToSpeechPosition(
                preserveReadAloudPosition = false,
                scrollMode = false,
                serviceChapterIndex = 4,
                serviceChapterPos = 200,
                loadedChapterIndex = 4,
                pageChapterPos = 500,
            )
        )
    }

    @Test
    fun crossChapterLoadNotPulledBySpeechSync() {
        assertFalse(
            shouldPullPageToSpeechPosition(
                preserveReadAloudPosition = false,
                scrollMode = false,
                serviceChapterIndex = 4,
                serviceChapterPos = 500,
                loadedChapterIndex = 5,
                pageChapterPos = 0,
            )
        )
    }

    @Test
    fun scrollModeKeepsExistingPauseSemantics() {
        assertFalse(
            shouldPullPageToSpeechPosition(
                preserveReadAloudPosition = false,
                scrollMode = true,
                serviceChapterIndex = 4,
                serviceChapterPos = 500,
                loadedChapterIndex = 4,
                pageChapterPos = 200,
            )
        )
    }

    @Test
    fun preservedRelayoutDoesNotMovePage() {
        assertFalse(
            shouldPullPageToSpeechPosition(
                preserveReadAloudPosition = true,
                scrollMode = false,
                serviceChapterIndex = 4,
                serviceChapterPos = 500,
                loadedChapterIndex = 4,
                pageChapterPos = 200,
            )
        )
    }
}
