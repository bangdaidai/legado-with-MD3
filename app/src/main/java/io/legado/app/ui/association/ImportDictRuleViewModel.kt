package io.legado.app.ui.association

import android.app.Application
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.DictRule
import io.legado.app.data.repository.DictRuleRepository
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.ui.widget.components.importComponents.BaseImportUiState
import io.legado.app.ui.widget.components.importComponents.ImportItemWrapper
import io.legado.app.ui.widget.components.importComponents.ImportStatus
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.readText
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import splitties.init.appCtx

class ImportDictRuleViewModel(
    app: Application,
    private val repository: DictRuleRepository,
) : BaseViewModel(app) {

    private val _uiState = MutableStateFlow<BaseImportUiState<DictRule>>(BaseImportUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<ImportDictRuleEffect>()
    val effects = _effects.asSharedFlow()

    private val allSources = arrayListOf<DictRule>()
    private val checkSources = arrayListOf<DictRule?>()

    fun onIntent(intent: ImportDictRuleIntent) {
        when (intent) {
            is ImportDictRuleIntent.ToggleItem -> toggleItem(intent.index)
            is ImportDictRuleIntent.ToggleAll -> toggleAll(intent.isSelected)
            is ImportDictRuleIntent.UpdateItem -> updateItem(intent.index, intent.data)
            is ImportDictRuleIntent.Import -> importSelect(intent.items)
            ImportDictRuleIntent.Dismiss -> {}
        }
    }

    private fun toggleItem(index: Int) {
        _uiState.update { state ->
            val success = state as? BaseImportUiState.Success ?: return@update state
            val items = success.items.toMutableList()
            if (index in items.indices) {
                items[index] = items[index].copy(isSelected = !items[index].isSelected)
            }
            success.copy(items = items)
        }
    }

    private fun toggleAll(isSelected: Boolean) {
        _uiState.update { state ->
            val success = state as? BaseImportUiState.Success ?: return@update state
            val items = success.items.map { it.copy(isSelected = isSelected) }
            success.copy(items = items)
        }
    }

    private fun updateItem(index: Int, data: DictRule) {
        _uiState.update { state ->
            val success = state as? BaseImportUiState.Success ?: return@update state
            val items = success.items.toMutableList()
            if (index in items.indices) {
                items[index] = items[index].copy(data = data)
            }
            success.copy(items = items)
        }
    }

    private fun importSelect(items: List<DictRule>) {
        execute {
            repository.insert(*items.toTypedArray())
        }.onSuccess {
            _uiState.value = BaseImportUiState.Idle
            _effects.emit(ImportDictRuleEffect.ImportFinished)
        }
    }

    fun importSource(text: String) {
        execute {
            _uiState.value = BaseImportUiState.Loading
            importSourceAwait(text.trim())
        }.onError {
            _uiState.value = BaseImportUiState.Error("ImportError:${it.localizedMessage}")
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onSuccess {
            comparisonSource()
        }
    }

    private suspend fun importSourceAwait(text: String) {
        when {
            text.isJsonObject() -> {
                GSON.fromJsonObject<DictRule>(text).getOrThrow().let {
                    allSources.add(it)
                }
            }
            text.isJsonArray() -> GSON.fromJsonArray<DictRule>(text).getOrThrow().let { items ->
                allSources.addAll(items)
            }
            text.isAbsUrl() -> {
                importSourceUrl(text)
            }
            text.isUri() -> {
                importSourceAwait(text.toUri().readText(appCtx))
            }
            else -> throw NoStackTraceException(context.getString(R.string.wrong_format))
        }
    }

    private suspend fun importSourceUrl(url: String) {
        okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().text().let {
            importSourceAwait(it)
        }
    }

    private fun comparisonSource() {
        execute {
            val wrappers = allSources.map { source ->
                val localSource = repository.findById(source.name)
                checkSources.add(localSource)
                val status = if (localSource == null) {
                    ImportStatus.New
                } else {
                    ImportStatus.Existing
                }
                ImportItemWrapper(
                    data = source,
                    oldData = localSource,
                    isSelected = status != ImportStatus.Existing,
                    status = status
                )
            }
            _uiState.value = BaseImportUiState.Success(
                source = "",
                items = wrappers
            )
        }
    }
}

sealed interface ImportDictRuleIntent {
    data class ToggleItem(val index: Int) : ImportDictRuleIntent
    data class ToggleAll(val isSelected: Boolean) : ImportDictRuleIntent
    data class UpdateItem(val index: Int, val data: DictRule) : ImportDictRuleIntent
    data class Import(val items: List<DictRule>) : ImportDictRuleIntent
    data object Dismiss : ImportDictRuleIntent
}

sealed interface ImportDictRuleEffect {
    data object ImportFinished : ImportDictRuleEffect
}
