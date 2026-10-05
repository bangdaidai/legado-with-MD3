package io.legado.app.domain.usecase

import io.legado.app.constant.BookType
import io.legado.app.data.entities.ShelfBookSummary
import io.legado.app.data.repository.BookRepository
import io.legado.app.domain.model.BookMatchKey
import io.legado.app.domain.model.ShelfDuplicateCopy
import io.legado.app.domain.model.ShelfDuplicateGroup
import io.legado.app.domain.model.ShelfDuplicateScanResult
import io.legado.app.help.book.formTypeMask
import kotlinx.collections.immutable.toImmutableList

/**
 * 扫描整个书架，找出「同一部作品存在多个副本」的情况。
 *
 * 刻意做成**只读报告**而不是清理工具：
 * - 书架本来就有「允许同名书籍」这个设置（`allowSameNameAuthorType`），同名副本常常是用户
 *   特意留的不同书源/不同版本，自动删哪一本都会误伤；
 * - 真要合并，正确路径是换源（[ChangeBookSourceUseCase]），它会把阅读进度、标签、阅读记忆
 *   一并迁到新 bookUrl；这个工具代劳不了，也不该代劳。
 */
class FindShelfDuplicatesUseCase(
    private val bookRepository: BookRepository,
) {

    suspend fun execute(): ShelfDuplicateScanResult {
        return groupShelfDuplicates(bookRepository.getShelfBookSummaries())
    }
}

/**
 * 纯分组逻辑，无 IO，可直接 JVM 单测。
 *
 * 判定口径与加入书架查重共用 [BookMatchKey]（NFKC + 空白折叠），形态走 [formTypeMask]，
 * 这样扫描结论不会与「加入书架时说不冲突」「阅读记录算一个副本还是两个」自相矛盾。
 *
 * 分组键是「规范化书名 + 形态掩码」，作者单独处理：[BookMatchKey.authorCompatible] 是
 * 「任一方为空即兼容」的**成对**谓词，不是等价关系，直接 groupBy 会得到依赖遍历顺序的结果。
 * 这里改成先按作者精确分桶，再在一个书名桶内合并空作者：
 * - 该书名下只有一个非空作者时，空作者副本并进去（旧数据的作者字段常缺失，这正是漏数的重灾区）；
 * - 有多个非空作者时（同名不同作者的作品），空作者副本**不并入任何一组**——它在两种归属里
 *   都可能是对的，强行并入就是凭空造出一个重复结论。
 *
 * 形态用「掩码相等」而不是 [formTypeMask] 意义上的位相交：审计工具宁可少报也不要误报，
 * 一本文本书和同名漫画并不是重复。
 */
internal fun groupShelfDuplicates(summaries: List<ShelfBookSummary>): ShelfDuplicateScanResult {
    val named = summaries.filter { BookMatchKey.of(it.name).isNotBlank() }
    val groups = named
        .groupBy { BookMatchKey.of(it.name) to it.type.formTypeMask }
        .values
        .flatMap { sameNameCopies ->
            val byAuthor = sameNameCopies.groupBy { BookMatchKey.of(it.author) }
            val blankAuthor = byAuthor.remove("").orEmpty()
            val namedAuthors = byAuthor.values
            // 作者为空且存在多个候选作者时不归属：宁可漏报也不误报
            val mergeBlank = namedAuthors.size <= 1
            namedAuthors.map { copies ->
                val merged = if (mergeBlank) copies + blankAuthor else copies
                if (merged.size < 2) null else merged.toDuplicateGroup()
            }
        }
        .filterNotNull()
        .sortedByDescending { it.copyCount }
    return ShelfDuplicateScanResult(
        scannedBookCount = summaries.size,
        groups = groups.toImmutableList(),
    )
}

private fun List<ShelfBookSummary>.toDuplicateGroup(): ShelfDuplicateGroup {
    // 最近阅读的排前面：报告是给"该留哪本"用的，先看最可能继续读的那本
    val ordered = sortedByDescending { it.durChapterTime }
    val first = ordered.first()
    return ShelfDuplicateGroup(
        name = first.name,
        author = first.author,
        copies = ordered.map { book ->
            ShelfDuplicateCopy(
                bookUrl = book.bookUrl,
                originName = book.originName,
                coverUrl = book.getDisplayCover(),
                isLocal = (book.type and BookType.local) > 0,
                totalChapterNum = book.totalChapterNum,
                durChapterIndex = book.durChapterIndex,
                durChapterTime = book.durChapterTime,
            )
        }.toImmutableList(),
    )
}