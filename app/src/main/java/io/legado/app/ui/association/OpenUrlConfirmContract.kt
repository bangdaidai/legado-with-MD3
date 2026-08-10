package io.legado.app.ui.association

import androidx.compose.runtime.Stable
import io.legado.app.constant.SourceType

@Stable
data class OpenUrlConfirmUiState(
    val sourceName: String = "",
    val showDeleteConfirm: Boolean = false,
)

sealed interface OpenUrlConfirmIntent {
    data class Init(
        val uri: String,
        val mimeType: String?,
        val sourceOrigin: String,
        val sourceName: String,
        @SourceType.Type val sourceType: Int,
    ) : OpenUrlConfirmIntent

    data object ConfirmOpen : OpenUrlConfirmIntent
    data object Cancel : OpenUrlConfirmIntent
    data object DisableSource : OpenUrlConfirmIntent
    data object RequestDeleteSource : OpenUrlConfirmIntent
    data object ConfirmDeleteSource : OpenUrlConfirmIntent
    data object DismissDeleteConfirm : OpenUrlConfirmIntent
}

sealed interface OpenUrlConfirmEffect {
    data class OpenUrl(val uri: String, val mimeType: String?) : OpenUrlConfirmEffect
    data object Finish : OpenUrlConfirmEffect
}
