package io.legado.app.ui.book.readingmemory.detail

import androidx.compose.runtime.Stable
import io.legado.app.data.entities.BookMarking
import io.legado.app.data.entities.BookTagGroup
import io.legado.app.data.entities.readRecord.ReadRecordTimelineDay
import io.legado.app.data.repository.ReadingStatistics
import io.legado.app.ui.book.shareCard.ShareCardScene
import io.legado.app.ui.widget.components.text.MarkingTextDecoration

/**
 * 书摘笔记的列表项。
 *
 * 原先 [ReadingMemoryDetailUiState.excerpts] 直接放 [BookMarking] 实体，由 Screen 自己
 * `GSON.fromJsonObject` 解析 anchorJson 取原文——JSON 解析是数据层职责，留在 Screen 既违反
 * UDF，也会随重组重复解析。这里把展示需要的字段在 ViewModel 一次算好；实体只作为编辑入口
 * 与分享卡片的数据源随 [raw] 带过。
 */
@Stable
data class ExcerptUi(
    val id: String,
    val chapterName: String,
    val note: String,
    /** 锚点里的选中文本，即书摘原文。 */
    val selectedText: String,
    /**
     * 划线样式（线型/线色/背景色/字体色），由 `styleJson` 解析而来。
     * 为 null 表示没有样式信息，按纯文本渲染。
     */
    val decoration: MarkingTextDecoration? = null,
    /** 编辑弹层与分享卡片需要原始实体。 */
    val raw: BookMarking,
)

/**
 * 阅读记忆详情页的 UI 状态。
 *
 * 注意：字段与 [io.legado.app.ui.book.readingmemory.detail.ReadingMemoryDetailViewModel]
 * 中实际产出的状态严格保持一致，新增/删除字段时请同步修改 ViewModel。
 */
data class ReadingMemoryDetailUiState(
    val bookUrl: String = "",
    val bookName: String = "",
    val author: String = "",
    val coverUrl: String? = null,
    val intro: String = "",
    val kind: String = "",
    val wordCount: Long = 0,
    val wordCountText: String = "",
    val rating: Float = 0f,
    val status: Int = 0,
    val statusText: String = "",
    val abandoned: Boolean = false,
    val isStillOnShelf: Boolean = true,
    val review: String = "",
    val userModifiedIntro: Boolean = false,
    val progress: Float = 0f,
    val progressInfo: String = "",
    val annotationCount: Int = 0,
    val lastReadTime: Long = 0L,
    val statistics: ReadingStatistics? = null,
    val protagonistNames: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val excerpts: List<ExcerptUi> = emptyList(),
    val readRecordTimelineDays: List<ReadRecordTimelineDay> = emptyList(),
    val readRecordTotalTime: Long = 0L,
    val availableTags: List<String> = emptyList(),
    val tagColorMap: Map<String, Long> = emptyMap(),
    val tagGroups: List<BookTagGroup> = emptyList(),
    val loading: Boolean = true,
    val showReviewEditor: Boolean = false,
    val reviewDraft: String = "",
    val showTagPicker: Boolean = false,
    val showRatingEditor: Boolean = false,
    val firstReadDate: String? = null,
    val totalReadWords: Long = 0L,
    val remainingWords: Long = 0L,
    val excerptCount: Int = 0,
    val totalChapterCount: Int = 0,
    val durChapterIndex: Int = 0,
    val bookshelfTagBorder: Boolean = false,
    val shareCardLoading: Boolean = false,
    val showShareCard: Boolean = false,
    val shareCardData: io.legado.app.data.entities.ShareCardData? = null,
    val shareCardScene: ShareCardScene? = null,
)


/**
 * 用户意图。与 ViewModel 的 [io.legado.app.ui.book.readingmemory.detail.ReadingMemoryDetailViewModel.handleIntent]
 * 分支一一对应，删除任意子类型都会导致 ViewModel 编译失败（exhaustive when）。
 */
sealed interface ReadingMemoryDetailIntent {
    data class Load(val bookUrl: String) : ReadingMemoryDetailIntent
    data class SetRating(val rating: Float) : ReadingMemoryDetailIntent
    data class OpenReviewEditor(val initial: String) : ReadingMemoryDetailIntent
    data class UpdateReviewDraft(val text: String) : ReadingMemoryDetailIntent
    data object SaveReview : ReadingMemoryDetailIntent
    data object DismissReviewEditor : ReadingMemoryDetailIntent
    data class SetStatus(val abandoned: Boolean) : ReadingMemoryDetailIntent
    data class EditIntro(val intro: String) : ReadingMemoryDetailIntent
    data object Refresh : ReadingMemoryDetailIntent
    data object NavigateBack : ReadingMemoryDetailIntent
    data object OpenTagPicker : ReadingMemoryDetailIntent
    data object DismissTagPicker : ReadingMemoryDetailIntent
    data object OpenTagEdit : ReadingMemoryDetailIntent
    data class AddTag(val tag: String) : ReadingMemoryDetailIntent
    data class RemoveTag(val tag: String) : ReadingMemoryDetailIntent
    data class AddProtagonist(val name: String) : ReadingMemoryDetailIntent
    data class RemoveProtagonist(val name: String) : ReadingMemoryDetailIntent
    data object OpenBookInfo : ReadingMemoryDetailIntent
    data object OpenBookInfoEdit : ReadingMemoryDetailIntent
    data object OpenBook : ReadingMemoryDetailIntent
    data object DeleteReview : ReadingMemoryDetailIntent
    data class EditMarking(val marking: BookMarking) : ReadingMemoryDetailIntent
    data class DeleteMarking(val id: String) : ReadingMemoryDetailIntent
    data object GenerateShareCard : ReadingMemoryDetailIntent
    data class GenerateShareCardFromMarking(val marking: BookMarking) : ReadingMemoryDetailIntent
    data object GenerateShareCardFromReview : ReadingMemoryDetailIntent
    data object DismissShareCard : ReadingMemoryDetailIntent
}

/**
 * 一次性副作用。
 */
sealed interface ReadingMemoryDetailEffect {
    data object Back : ReadingMemoryDetailEffect
    data class ShowToast(val message: String) : ReadingMemoryDetailEffect
    data class NavigateToBookInfo(
        val name: String,
        val author: String,
        val bookUrl: String,
    ) : ReadingMemoryDetailEffect
    data class OpenBookInfoEdit(val bookUrl: String) : ReadingMemoryDetailEffect
    data class OpenReadBook(val bookUrl: String) : ReadingMemoryDetailEffect
}
