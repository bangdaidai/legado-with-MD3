package io.legado.app.ui.book.readingmemory.detail

import io.legado.app.base.UiEvent
import io.legado.app.base.UiState
import io.legado.app.data.repository.ReadingMemoryRepository.ReadingStatistics

/** 阅读记忆详情页状态 */
data class ReadingMemoryDetailUiState(
    val bookUrl: String = "",
    val bookName: String = "",
    val author: String = "",
    val coverUrl: String? = null,
    val intro: String = "",
    val kind: String = "",
    val wordCount: Long = 0,
    val rating: Int = 0,
    val status: Int = 0,
    val abandoned: Boolean = false,
    val isStillOnShelf: Boolean = true,
    val review: String = "",
    val userModifiedIntro: String? = null,
    val progressInfo: String = "",
    val statistics: ReadingStatistics? = null,
    val protagonistNames: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val loading: Boolean = true,
    val showReviewEditor: Boolean = false,
    val reviewDraft: String = "",
    val showAbandonedDialog: Boolean = false,
    val showRatingEditor: Boolean = false,
) : UiState

sealed interface ReadingMemoryDetailIntent {
    data class Load(val bookUrl: String) : ReadingMemoryDetailIntent
    data class SetRating(val rating: Int) : ReadingMemoryDetailIntent
    data class OpenReviewEditor(val initial: String) : ReadingMemoryDetailIntent
    data class UpdateReviewDraft(val text: String) : ReadingMemoryDetailIntent
    data object SaveReview : ReadingMemoryDetailIntent
    data object DismissReviewEditor : ReadingMemoryDetailIntent
    data class ToggleAbandoned(val abandoned: Boolean) : ReadingMemoryDetailIntent
    data object ConfirmAbandoned : ReadingMemoryDetailIntent
    data object DismissAbandonedDialog : ReadingMemoryDetailIntent
    data class EditIntro(val intro: String) : ReadingMemoryDetailIntent
    data object Refresh : ReadingMemoryDetailIntent
    data object NavigateBack : ReadingMemoryDetailIntent
}

sealed interface ReadingMemoryDetailEffect {
    data object Back : ReadingMemoryDetailEffect
    data class ShowToast(val message: String) : ReadingMemoryDetailEffect
}
