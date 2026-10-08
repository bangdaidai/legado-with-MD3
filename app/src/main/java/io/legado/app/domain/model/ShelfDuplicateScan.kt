package io.legado.app.domain.model

import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/**
 * 书架重名扫描里的一个副本。
 *
 * 只带"判断该留哪本"需要的信息：书源、进度、最近阅读时间。刻意不带 intro / variable 等重字段，
 * 也不带 group —— 分组差异不影响去留判断，不值得为此把分组名一起查出来。
 */
@Stable
data class ShelfDuplicateCopy(
    val bookUrl: String,
    val originName: String,
    val coverUrl: String?,
    val isLocal: Boolean,
    val totalChapterNum: Int,
    val durChapterIndex: Int,
    val durChapterTime: Long,
) {
    /** 进度 "已读/总章"，无目录时只显示已读数占位，避免出现 0/0 这种自相矛盾的文案。 */
    fun progressText(): String = if (totalChapterNum > 0) {
        "${durChapterIndex + 1}/$totalChapterNum"
    } else {
        "${durChapterIndex + 1}"
    }
}

/**
 * 一组被判定为「同一部作品」的同名副本（数量 ≥ 2）。
 *
 * 判定口径见 [io.legado.app.domain.usecase.FindShelfDuplicatesUseCase]，
 * 与加入书架查重共用 [BookMatchKey]。
 */
@Stable
data class ShelfDuplicateGroup(
    val name: String,
    val author: String,
    val copies: ImmutableList<ShelfDuplicateCopy>,
) {
    val copyCount: Int get() = copies.size
}

/** 一次书架重名扫描的结果。 */
@Stable
data class ShelfDuplicateScanResult(
    val scannedBookCount: Int = 0,
    val groups: ImmutableList<ShelfDuplicateGroup> = persistentListOf(),
) {
    val duplicateGroupCount: Int get() = groups.size

    /** 落在重复组里的副本总数（不是"多余"的本数，每组保留一本的话才等于副本数 - 组数）。 */
    val duplicateCopyCount: Int get() = groups.sumOf { it.copyCount }

    /**
     * 面板内删除一个副本后的新结果：组内剩不到两本时整组不再是重复，一并移除。
     * 纯函数，删除落库后本地同步用，避免为此再扫一遍全库。
     */
    fun withoutCopy(bookUrl: String): ShelfDuplicateScanResult {
        val newGroups = groups.mapNotNull { group ->
            val copies = group.copies.filter { it.bookUrl != bookUrl }
            when {
                copies.size == group.copyCount -> group
                copies.size >= 2 -> group.copy(copies = copies.toImmutableList())
                else -> null
            }
        }.toImmutableList()
        return copy(groups = newGroups)
    }
}