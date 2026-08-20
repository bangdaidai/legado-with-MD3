package io.legado.app.ui.main.my.authorManage

import io.legado.app.data.entities.ReadingMemory

/** 是否算作"已读完"：未弃读且进度达到 100%。 */
internal fun isAuthorBookFinished(memory: ReadingMemory): Boolean =
    !memory.abandoned && memory.progress >= 1f

/** 已读书籍评分的平均分（仅统计有评分的已读书，否则为 0）。 */
internal fun authorAvgRating(memories: List<ReadingMemory>): Float {
    val rated = memories.filter { it.rating > 0f }
    if (rated.isEmpty()) return 0f
    return (rated.sumOf { it.rating.toDouble() } / rated.size).toFloat()
}
