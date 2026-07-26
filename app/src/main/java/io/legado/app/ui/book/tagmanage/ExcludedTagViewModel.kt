package io.legado.app.ui.book.tagmanage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.ExcludedTag
import io.legado.app.help.book.TagManager
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExcludedTagViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ExcludedTagUiState())
    val uiState = _uiState.asStateFlow()

    private val _effect = MutableSharedFlow<ExcludedTagEffect>()
    val effect: SharedFlow<ExcludedTagEffect> = _effect.asSharedFlow()

    init {
        loadData()
    }

    fun sendEvent(intent: ExcludedTagIntent) {
        when (intent) {
            is ExcludedTagIntent.Search ->
                _uiState.value = _uiState.value.copy(searchQuery = intent.q)

            ExcludedTagIntent.Refresh -> loadData()

            is ExcludedTagIntent.SaveExcluded -> viewModelScope.launch { saveExcluded(intent) }
            is ExcludedTagIntent.DeleteExcluded -> viewModelScope.launch { deleteExcluded(intent.excluded) }
        }
    }

    private fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = appDb.excludedTagDao.getAllSync()
            _uiState.value = _uiState.value.copy(excludedTags = list.toImmutableList())
        }
    }

    private suspend fun saveExcluded(intent: ExcludedTagIntent.SaveExcluded) {
        if (intent.name.isBlank()) {
            _effect.emit(ExcludedTagEffect.ShowMessage("排除项不能为空"))
            return
        }
        if (intent.isRegex && !TagManager.isValidRegex(intent.name)) {
            _effect.emit(ExcludedTagEffect.ShowMessage("正则格式不正确"))
            return
        }
        if (intent.id == 0L) {
            appDb.excludedTagDao.insert(ExcludedTag(name = intent.name, isRegex = intent.isRegex))
        } else {
            val old = appDb.excludedTagDao.getById(intent.id) ?: return
            appDb.excludedTagDao.update(old.copy(name = intent.name, isRegex = intent.isRegex))
        }
        loadData()
    }

    private suspend fun deleteExcluded(excluded: ExcludedTag) {
        appDb.excludedTagDao.deleteById(excluded.id)
        loadData()
    }
}
