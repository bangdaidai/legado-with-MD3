package io.legado.app.ui.association

import android.app.Application
import android.util.Base64
import androidx.core.net.toUri
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.repository.HttpTtsRepository
import io.legado.app.domain.model.readaloud.HttpTtsCatalogDraft
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.ui.widget.components.importComponents.BaseImportUiState
import io.legado.app.ui.widget.components.importComponents.ImportItemWrapper
import io.legado.app.ui.widget.components.importComponents.ImportStatus
import io.legado.app.help.readaloud.speech.HttpTtsCatalogParser
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isDataUrl
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

class ImportHttpTtsViewModel(
    app: Application,
    private val repository: HttpTtsRepository,
) : BaseViewModel(app) {

    private val _uiState = MutableStateFlow<BaseImportUiState<HttpTTS>>(BaseImportUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<ImportHttpTtsEffect>()
    val effects = _effects.asSharedFlow()
    /** 与 [allSources] 同下标：源自带的发言人/情绪目录，没有就是空草稿。 */
    private val catalogDrafts = arrayListOf<HttpTtsCatalogDraft>()

    private val allSources = arrayListOf<HttpTTS>()
    private val checkSources = arrayListOf<HttpTTS?>()

    fun onIntent(intent: ImportHttpTtsIntent) {
        when (intent) {
            is ImportHttpTtsIntent.ToggleItem -> toggleItem(intent.index)
            is ImportHttpTtsIntent.ToggleAll -> toggleAll(intent.isSelected)
            is ImportHttpTtsIntent.UpdateItem -> updateItem(intent.index, intent.data)
            is ImportHttpTtsIntent.Import -> importSelect(intent.items)
            ImportHttpTtsIntent.Dismiss -> {}
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

    private fun updateItem(index: Int, data: HttpTTS) {
        _uiState.update { state ->
            val success = state as? BaseImportUiState.Success ?: return@update state
            val items = success.items.toMutableList()
            if (index in items.indices) {
                items[index] = items[index].copy(data = data)
            }
            success.copy(items = items)
        }
    }

    private fun importSelect(items: List<HttpTTS>) {
        execute {
            // 目录落 read_aloud_voices，不写回源行；没带目录的源由 UseCase 自己跳过。
            // 导入与目录写入同一事务：中途失败整体回滚，不会留下「有源没音色」的半截状态。
            repository.importWithCatalogs(
                items.map { source ->
                    source to catalogDrafts.getOrElse(allSources.indexOf(source)) { HttpTtsCatalogDraft() }
                }
            )
        }.onSuccess {
            _uiState.value = BaseImportUiState.Idle
            _effects.emit(ImportHttpTtsEffect.ImportFinished)
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
                val draft = HttpTtsCatalogParser.parse(text)
                HttpTTS.fromJson(text).getOrThrow().let {
                    allSources.add(it)
                    catalogDrafts.add(draft)
                }
            }
            text.isJsonArray() -> {
                val drafts = HttpTtsCatalogParser.parseList(text)
                HttpTTS.fromJsonArray(text).getOrThrow().let { items ->
                    items.forEachIndexed { index, source ->
                        allSources.add(source)
                        catalogDrafts.add(drafts.getOrElse(index) { HttpTtsCatalogDraft() })
                    }
                }
            }
            text.isDataUrl() -> {
                val data = AppPattern.dataUriRegex.find(text)?.groupValues?.getOrNull(1)
                    ?: throw NoStackTraceException(context.getString(R.string.wrong_format))
                importSourceAwait(Base64.decode(data, Base64.DEFAULT).toString(Charsets.UTF_8))
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
                val localSource = repository.findById(source.id)
                checkSources.add(localSource)
                val status = when {
                    localSource == null -> ImportStatus.New
                    source.lastUpdateTime > localSource.lastUpdateTime -> ImportStatus.Update
                    else -> ImportStatus.Existing
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

sealed interface ImportHttpTtsIntent {
    data class ToggleItem(val index: Int) : ImportHttpTtsIntent
    data class ToggleAll(val isSelected: Boolean) : ImportHttpTtsIntent
    data class UpdateItem(val index: Int, val data: HttpTTS) : ImportHttpTtsIntent
    data class Import(val items: List<HttpTTS>) : ImportHttpTtsIntent
    data object Dismiss : ImportHttpTtsIntent
}

sealed interface ImportHttpTtsEffect {
    data object ImportFinished : ImportHttpTtsEffect
}
