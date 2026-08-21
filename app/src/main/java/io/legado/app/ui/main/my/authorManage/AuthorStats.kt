package io.legado.app.ui.main.my.authorManage

import android.icu.text.AlphabeticIndex
import android.icu.util.ULocale
import io.legado.app.data.entities.ReadingMemory

/** 是否算作"已读完"：未弃读且进度达到 100%。 */
internal fun isAuthorBookFinished(memory: ReadingMemory): Boolean =
    !memory.abandoned && memory.progress >= 1f

/** 已打分书籍的平均分（无评分时为 0），只要打过分就参与统计。 */
internal fun authorAvgRating(memories: List<ReadingMemory>): Float {
    val rated = memories.filter { it.rating > 0f }
    if (rated.isEmpty()) return 0f
    return (rated.sumOf { it.rating.toDouble() } / rated.size).toFloat()
}

/** 索引分组用的桶，中文按拼音首字母归类，与 cnCompare 使用同一套简体中文排序规则。 */
private val alphabeticIndex by lazy {
    AlphabeticIndex<Unit>(ULocale.SIMPLIFIED_CHINESE)
        .addLabels(ULocale.ENGLISH)
        .buildImmutableIndex()
}

/** 作者名对应的索引标签，数字/符号等落到 "#"。 */
internal fun authorIndexLabel(name: String): String {
    if (name.isBlank()) return "#"
    val bucket = alphabeticIndex.getBucket(alphabeticIndex.getBucketIndex(name))
    return if (bucket?.labelType == AlphabeticIndex.Bucket.LabelType.NORMAL) bucket.label else "#"
}
