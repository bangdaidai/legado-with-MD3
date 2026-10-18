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

/**
 * 「多角色分配」「背景音乐分配」这类**既改正文标记、又影响朗读计划**的开关的统一处理。
 *
 * 两件事缺一不可：
 * 1. 重载正文——标记是「多角色分配」注入正文的那一份，胶囊与 ♪ 都从正文元素渲染，
 *    不重排就看不到任何变化；
 * 2. 重启朗读管线——正文变了，`contentHash` 变，按旧内容建的分析计划不再对得上。
 *
 * 听书页过去只写设置、什么都不做，界面上开关已经是新的、正在念的还是旧标记那一套。
 */
internal suspend fun applyReaderRenderedSwitch(
    readAloudSessionStore: ReadAloudSessionStore,
    context: Context,
    /**
     * 重载正文时是否把当前页交给朗读服务同步。
     *
     * 只有"朗读服务正在读的就是这一章"才该保位，否则会把读过半章的朗读拽回章首。
     * 调用方（阅读页）有更严的判定（还要比对章序号），所以由它传进来。
     */
    preserveReadAloudPosition: Boolean = ReadBook.isCurrentBookReadAloud,
): Boolean {
    val shouldRestart = ReadBook.isCurrentBookReadAloud
    ReadBook.clearTextChapter()
    ReadBook.loadContent(
        resetPageOffset = false,
        preserveReadAloudPosition = preserveReadAloudPosition,
    )
    if (!shouldRestart) return false
    return restartReadAloudPipeline(readAloudSessionStore, context)
}
