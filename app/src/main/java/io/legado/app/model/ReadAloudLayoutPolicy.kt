package io.legado.app.model

internal fun shouldRestartReadAloudAfterContentLoad(
    preserveReadAloudPosition: Boolean,
    serviceChapterIndex: Int,
    loadedChapterIndex: Int,
): Boolean = !preserveReadAloudPosition || serviceChapterIndex != loadedChapterIndex

/**
 * 内容加载/分页批次落地时，是否应改为「页面追声音」而不是按当前页重起一轮。
 *
 * 听书页盖住阅读页期间排版不产出，声音驱动的翻页（moveToNextPage）拿不到页表而静默失败，
 * 可见页冻在离开那一页、声音继续往前。返回后批次落地走旧的跟随重启＝从页首重起一轮，
 * 表现即「阅读页停在原来那页，听书从那页最开始重读」。仅当全部满足才接管对齐：
 * 这次加载原本会重起轮（保留位置的重排不掺和）、非滚动模式（滚动有既有 pause 语义，不动）、
 * 声音与页面在同一章、且声音位置已读过可见页页首（声音领先）。
 * 页面领先（用户刚手动跳到后面的页）时不接管，维持「声音跟页」的旧语义。
 */
internal fun shouldPullPageToSpeechPosition(
    preserveReadAloudPosition: Boolean,
    scrollMode: Boolean,
    serviceChapterIndex: Int,
    serviceChapterPos: Int,
    loadedChapterIndex: Int,
    pageChapterPos: Int,
): Boolean = !preserveReadAloudPosition &&
        !scrollMode &&
        serviceChapterIndex == loadedChapterIndex &&
        serviceChapterPos > pageChapterPos

internal fun activeReadAloudProgress(
    isPlaying: Boolean,
    currentProgress: Int,
): Int? = currentProgress.takeIf { isPlaying && it > 0 }
