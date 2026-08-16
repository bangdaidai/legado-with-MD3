package io.legado.app.ui.book.tagmanage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.ExcludedTag
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.help.book.TagManager
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ExcludedTagViewModel(
    private val bookshelfSettingsGateway: BookshelfSettingsGateway,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExcludedTagUiState())
    val uiState = _uiState.asStateFlow()

    private val _effect = MutableSharedFlow<ExcludedTagEffect>()
    val effect: SharedFlow<ExcludedTagEffect> = _effect.asSharedFlow()

    init {
        loadData()
        viewModelScope.launch {
            bookshelfSettingsGateway.settings.collect { settings ->
                _uiState.update {
                    it.copy(bookshelfTagBorder = settings.bookshelfTagBorder)
                }
            }
        }
    }

    fun sendEvent(intent: ExcludedTagIntent) {
        when (intent) {
            is ExcludedTagIntent.Search ->
                _uiState.update { it.copy(searchQuery = intent.q) }

            ExcludedTagIntent.Refresh -> loadData()

            is ExcludedTagIntent.SaveExcluded -> viewModelScope.launch { saveExcluded(intent) }
            is ExcludedTagIntent.DeleteExcluded -> viewModelScope.launch { deleteExcluded(intent.excluded) }
        }
    }

    private fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = appDb.excludedTagDao.getAllSync()
            _uiState.update {
                it.copy(
                    version = System.currentTimeMillis(),
                    excludedTags = list.toImmutableList(),
                )
            }
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
        val (removed, restored) = TagManager.reconcileTagsWithExclusion()
        _effect.emit(
            ExcludedTagEffect.ShowMessage(
                buildString {
                    append("已保存排除项")
                    if (removed > 0) append("，移除 $removed 个标签")
                    if (restored > 0) append("，恢复 $restored 个标签")
                },
            ),
        )
    }

    private suspend fun deleteExcluded(excluded: ExcludedTag) {
        appDb.excludedTagDao.deleteById(excluded.id)
        loadData()
        val (_, restored) = TagManager.reconcileTagsWithExclusion()
        _effect.emit(
            ExcludedTagEffect.ShowMessage(
                if (restored > 0) "已删除排除项，已自动恢复 $restored 个被排除的标签" else "已删除排除项",
            ),
        )
    }
}
