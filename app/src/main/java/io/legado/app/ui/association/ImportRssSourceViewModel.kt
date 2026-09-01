package io.legado.app.ui.association

import android.app.Application
import androidx.core.net.toUri
import com.jayway.jsonpath.JsonPath
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.RssSource
import io.legado.app.data.repository.RssRepository
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
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
import io.legado.app.utils.jsonPath
import io.legado.app.utils.readText
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import splitties.init.appCtx

class ImportRssSourceViewModel(
    app: Application,
    private val repository: RssRepository,
) : BaseViewModel(app) {

    private val _uiState = MutableStateFlow<BaseImportUiState<RssSource>>(BaseImportUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<ImportRssSourceEffect>()
    val effects = _effects.asSharedFlow()

    var isAddGroup = false
    var groupName: String? = null

    private val allSources = arrayListOf<RssSource>()
    private val checkSources = arrayListOf<RssSource?>()

    fun onIntent(intent: ImportRssSourceIntent) {
        when (intent) {
            is ImportRssSourceIntent.ToggleItem -> toggleItem(intent.index)
            is ImportRssSourceIntent.ToggleAll -> toggleAll(intent.isSelected)
            is ImportRssSourceIntent.UpdateItem -> updateItem(intent.index, intent.data)
            is ImportRssSourceIntent.SetCustomGroup -> {
                isAddGroup = intent.isAdd
                groupName = intent.group
            }
            is ImportRssSourceIntent.Import -> importSelect(intent.items)
            ImportRssSourceIntent.Dismiss -> {}
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

    private fun updateItem(index: Int, data: RssSource) {
        _uiState.update { state ->
            val success = state as? BaseImportUiState.Success ?: return@update state
            val items = success.items.toMutableList()
            if (index in items.indices) {
                items[index] = items[index].copy(data = data)
            }
            success.copy(items = items)
        }
    }

    private fun importSelect(items: List<RssSource>) {
        execute {
            val group = groupName?.trim()
            val keepName = AppConfig.importKeepName
            val keepGroup = AppConfig.importKeepGroup
            val keepEnable = AppConfig.importKeepEnable
            val selectSource = arrayListOf<RssSource>()
            items.forEach { source ->
                val checkSource = checkSources.find { it?.sourceUrl == source.sourceUrl }
                checkSource?.let {
                    if (keepName) {
                        source.sourceName = it.sourceName
                    }
                    if (keepGroup) {
                        source.sourceGroup = it.sourceGroup
                    }
                    if (keepEnable) {
                        source.enabled = it.enabled
                    }
                    source.customOrder = it.customOrder
                }
                if (!group.isNullOrEmpty()) {
                    if (isAddGroup) {
                        val groups = linkedSetOf<String>()
                        source.sourceGroup?.splitNotBlank(AppPattern.splitGroupRegex)?.let {
                            groups.addAll(it)
                        }
                        groups.add(group)
                        source.sourceGroup = groups.joinToString(",")
                    } else {
                        source.sourceGroup = group
                    }
                }
                selectSource.add(source)
            }
            SourceHelp.insertRssSource(*selectSource.toTypedArray())
        }.onSuccess {
            _uiState.value = BaseImportUiState.Idle
            _effects.emit(ImportRssSourceEffect.ImportFinished)
        }
    }

    fun importSource(text: String) {
        execute {
            _uiState.value = BaseImportUiState.Loading
            importSourceAwait(text)
        }.onError {
            _uiState.value = BaseImportUiState.Error("ImportError:${it.localizedMessage}")
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onSuccess {
            comparisonSource()
        }
    }

    private suspend fun importSourceAwait(text: String) {
        val mText = text.trim()
        when {
            mText.isJsonObject() -> kotlin.runCatching {
                val json = JsonPath.parse(mText)
                val urls = json.read<List<String>>("$.sourceUrls")
                if (!urls.isNullOrEmpty()) {
                    urls.forEach {
                        importSourceUrl(it)
                    }
                }
            }.onFailure {
                GSON.fromJsonArray<RssSource>(mText).getOrThrow().let {
                    val source = it.firstOrNull() ?: return@let
                    if (source.sourceUrl.isEmpty()) {
                        throw NoStackTraceException("不是订阅源")
                    }
                    allSources.addAll(it)
                }
            }

            mText.isJsonArray() -> {
                GSON.fromJsonArray<RssSource>(mText).getOrThrow().let {
                    val source = it.firstOrNull() ?: return@let
                    if (source.sourceUrl.isEmpty()) {
                        throw NoStackTraceException("不是订阅源")
                    }
                    allSources.addAll(it)
                }
            }

            mText.isAbsUrl() -> {
                importSourceUrl(mText)
            }

            mText.isUri() -> {
                importSourceAwait(mText.toUri().readText(appCtx))
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
        }.decompressed().byteStream().use { body ->
            val items = jsonPath.parse(body).read<List<*>>("$")
            for (item in items) {
                if (item !is Map<*, *>) {
                    throw NoStackTraceException("不是订阅源")
                }
                if (!item.containsKey("sourceUrl")) {
                    throw NoStackTraceException("不是订阅源")
                }
                val jsonItem = jsonPath.parse(item)
                GSON.fromJsonObject<RssSource>(jsonItem.jsonString()).getOrThrow()
                    .let { source ->
                        allSources.add(source)
                    }
            }
        }
    }

    private fun comparisonSource() {
        execute {
            val wrappers = allSources.map { source ->
                val localSource = repository.getByKey(source.sourceUrl)
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

sealed interface ImportRssSourceIntent {
    data class ToggleItem(val index: Int) : ImportRssSourceIntent
    data class ToggleAll(val isSelected: Boolean) : ImportRssSourceIntent
    data class UpdateItem(val index: Int, val data: RssSource) : ImportRssSourceIntent
    data class SetCustomGroup(val group: String, val isAdd: Boolean) : ImportRssSourceIntent
    data class Import(val items: List<RssSource>) : ImportRssSourceIntent
    data object Dismiss : ImportRssSourceIntent
}

sealed interface ImportRssSourceEffect {
    data object ImportFinished : ImportRssSourceEffect
}
