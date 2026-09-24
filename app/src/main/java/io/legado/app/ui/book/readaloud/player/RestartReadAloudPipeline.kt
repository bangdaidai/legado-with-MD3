package io.legado.app.ui.book.readaloud.player

import android.content.Context
import io.legado.app.domain.model.readaloud.ReadAloudSessionStatus
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadAloudSessionStore
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 正在朗读时音色/管线相关配置变化后，重启合成管线让当前章按新配置重建朗读计划。
 *
 * 朗读计划（每个角色用哪个音色）是章准备时一次性建好放进播放队列的，之后只改数据库绑定
 * 不会自动生效；必须走 停止 → 等状态回 Idle → 重评引擎类 → 按页内位置重放 这一整套，
 * 且要等服务真的停下再重启，避免新旧管线叠音。多角色开关切换与配音页改绑定共用这条路径。
 *
 * @return true 表示确实执行了重启；没在朗读或等待超时返回 false，调用方无需额外处理。
 */
internal suspend fun restartReadAloudPipeline(
    readAloudSessionStore: ReadAloudSessionStore,
    context: Context,
): Boolean {
    if (!BaseReadAloudService.isRun) return false
    if (ReadBook.readerChapterInputWindow.current == null) return false
    val resumePlaying = !BaseReadAloudService.pause
    val chapterPosition = readAloudSessionStore.state.value.playback.chapterPosition
    ReadAloud.stop(context)
    val stopped = withTimeoutOrNull(2_000) {
        readAloudSessionStore.state.first { it.status == ReadAloudSessionStatus.Idle }
    }
    if (stopped == null) return false
    ReadAloud.refreshReadAloudClass()
    ReadAloud.play(
        context = context,
        play = resumePlaying,
        chapterPosition = chapterPosition.coerceAtLeast(0),
    )
    return true
}
