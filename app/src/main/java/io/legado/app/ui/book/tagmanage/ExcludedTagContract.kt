package io.legado.app.ui.book.tagmanage

import io.legado.app.data.entities.ExcludedTag
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

data class ExcludedTagUiState(
    val searchQuery: String = "",
    val excludedTags: ImmutableList<ExcludedTag> = persistentListOf(),
)

sealed interface ExcludedTagIntent {
    data class Search(val q: String) : ExcludedTagIntent
    data object Refresh : ExcludedTagIntent
    data class SaveExcluded(val id: Long, val name: String, val isRegex: Boolean) : ExcludedTagIntent
    data class DeleteExcluded(val excluded: ExcludedTag) : ExcludedTagIntent
}

sealed interface ExcludedTagEffect {
    data class ShowMessage(val message: String) : ExcludedTagEffect
}
