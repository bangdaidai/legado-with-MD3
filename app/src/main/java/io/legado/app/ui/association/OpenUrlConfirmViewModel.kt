package io.legado.app.ui.association

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.constant.SourceType
import io.legado.app.help.source.SourceHelp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OpenUrlConfirmViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(OpenUrlConfirmUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<OpenUrlConfirmEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    private var uri = ""
    private var mimeType: String? = null
    private var sourceOrigin = ""
    private var sourceType = SourceType.book

    fun onIntent(intent: OpenUrlConfirmIntent) {
        when (intent) {
            is OpenUrlConfirmIntent.Init -> init(intent)
            OpenUrlConfirmIntent.ConfirmOpen -> confirmOpen()
            OpenUrlConfirmIntent.Cancel -> _effects.tryEmit(OpenUrlConfirmEffect.Finish)
            OpenUrlConfirmIntent.DisableSource -> disableSource()
            OpenUrlConfirmIntent.RequestDeleteSource ->
                _uiState.update { it.copy(showDeleteConfirm = true) }

            OpenUrlConfirmIntent.DismissDeleteConfirm ->
                _uiState.update { it.copy(showDeleteConfirm = false) }

            OpenUrlConfirmIntent.ConfirmDeleteSource -> deleteSource()
        }
    }

    private fun init(intent: OpenUrlConfirmIntent.Init) {
        uri = intent.uri
        mimeType = intent.mimeType
        sourceOrigin = intent.sourceOrigin
        sourceType = intent.sourceType
        _uiState.update { it.copy(sourceName = intent.sourceName) }
    }

    private fun confirmOpen() {
        if (uri.isNotBlank()) {
            _effects.tryEmit(OpenUrlConfirmEffect.OpenUrl(uri, mimeType))
        } else {
            _effects.tryEmit(OpenUrlConfirmEffect.Finish)
        }
    }

    private fun disableSource() {
        viewModelScope.launch(Dispatchers.IO) {
            SourceHelp.enableSource(sourceOrigin, sourceType, false)
            _effects.tryEmit(OpenUrlConfirmEffect.Finish)
        }
    }

    private fun deleteSource() {
        viewModelScope.launch(Dispatchers.IO) {
            SourceHelp.deleteSource(sourceOrigin, sourceType)
            _effects.tryEmit(OpenUrlConfirmEffect.Finish)
        }
    }
}
