package io.legado.app.ui.association

import android.app.Application
import androidx.core.net.toUri
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.repository.ReplaceRuleRepository
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.ReplaceAnalyzer
import io.legado.app.help.http.decompressed
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.ui.widget.components.importComponents.BaseImportUiState
import io.legado.app.ui.widget.components.importComponents.ImportItemWrapper
import io.legado.app.ui.widget.components.importComponents.ImportStatus
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.isUri
import io.legado.app.utils.readText
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import splitties.init.appCtx

class ImportReplaceRuleViewModel(
    app: Application,
    private val repository: ReplaceRuleRepository,
) : BaseViewModel(app) {

    private val _uiState = MutableStateFlow<BaseImportUiState<ReplaceRule>>(BaseImportUiState.Idle)
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<ImportReplaceRuleEffect>()
    val effects = _effects.asSharedFlow()

    var isAddGroup = false
    var groupName: String? = null

    private val allRules = arrayListOf<ReplaceRule>()
    private val checkRules = arrayListOf<ReplaceRule?>()

    fun onIntent(intent: ImportReplaceRuleIntent) {
        when (intent) {
            is ImportReplaceRuleIntent.ToggleItem -> toggleItem(intent.index)
            is ImportReplaceRuleIntent.ToggleAll -> toggleAll(intent.isSelected)
            is ImportReplaceRuleIntent.UpdateItem -> updateItem(intent.index, intent.data)
            is ImportReplaceRuleIntent.SetCustomGroup -> {
                isAddGroup = intent.isAdd
                groupName = intent.group
            }
            is ImportReplaceRuleIntent.Import -> importSelect(intent.items)
            ImportReplaceRuleIntent.Dismiss -> {}
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

    private fun updateItem(index: Int, data: ReplaceRule) {
        _uiState.update { state ->
            val success = state as? BaseImportUiState.Success ?: return@update state
            val items = success.items.toMutableList()
            if (index in items.indices) {
                items[index] = items[index].copy(data = data)
            }
            success.copy(items = items)
        }
    }

    private fun importSelect(items: List<ReplaceRule>) {
        execute {
            val group = groupName?.trim()
            val selectRules = arrayListOf<ReplaceRule>()
            items.forEach { rule ->
                if (!group.isNullOrEmpty()) {
                    if (isAddGroup) {
                        val groups = linkedSetOf<String>()
                        rule.group?.splitNotBlank(AppPattern.splitGroupRegex)?.let {
                            groups.addAll(it)
                        }
                        groups.add(group)
                        rule.group = groups.joinToString(",")
                    } else {
                        rule.group = group
                    }
                }
                selectRules.add(rule)
            }
            repository.insert(*selectRules.toTypedArray())
        }.onSuccess {
            _effects.emit(ImportReplaceRuleEffect.ImportFinished)
        }
    }

    fun import(text: String) {
        execute {
            _uiState.value = BaseImportUiState.Loading
            importAwait(text.trim())
        }.onError {
            _uiState.value = BaseImportUiState.Error("ImportError:${it.localizedMessage}")
            AppLog.put("ImportError:${it.localizedMessage}", it)
        }.onSuccess {
            comparisonSource()
        }
    }

    private suspend fun importAwait(text: String) {
        when {
            text.isAbsUrl() -> importUrl(text)
            text.isJsonArray() -> {
                val rules = ReplaceAnalyzer.jsonToReplaceRules(text).getOrThrow()
                allRules.addAll(rules)
            }

            text.isJsonObject() -> {
                val rule = ReplaceAnalyzer.jsonToReplaceRule(text).getOrThrow()
                allRules.add(rule)
            }

            text.isUri() -> {
                importAwait(text.toUri().readText(appCtx))
            }

            else -> throw NoStackTraceException("格式不对")
        }
    }

    private suspend fun importUrl(url: String) {
        okHttpClient.newCallResponseBody {
            if (url.endsWith("#requestWithoutUA")) {
                url(url.substringBeforeLast("#requestWithoutUA"))
                header(AppConst.UA_NAME, "null")
            } else {
                url(url)
            }
        }.decompressed().text("utf-8").let {
            importAwait(it)
        }
    }

    private fun comparisonSource() {
        execute {
            val wrappers = allRules.map { rule ->
                val localRule = repository.findById(rule.id)
                checkRules.add(localRule)
                val status = if (localRule == null) {
                    ImportStatus.New
                } else if (rule.pattern != localRule.pattern ||
                    rule.replacement != localRule.replacement ||
                    rule.isRegex != localRule.isRegex ||
                    rule.scope != localRule.scope
                ) {
                    ImportStatus.Update
                } else {
                    ImportStatus.Existing
                }
                ImportItemWrapper(
                    data = rule,
                    oldData = localRule,
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

sealed interface ImportReplaceRuleIntent {
    data class ToggleItem(val index: Int) : ImportReplaceRuleIntent
    data class ToggleAll(val isSelected: Boolean) : ImportReplaceRuleIntent
    data class UpdateItem(val index: Int, val data: ReplaceRule) : ImportReplaceRuleIntent
    data class SetCustomGroup(val group: String, val isAdd: Boolean) : ImportReplaceRuleIntent
    data class Import(val items: List<ReplaceRule>) : ImportReplaceRuleIntent
    data object Dismiss : ImportReplaceRuleIntent
}

sealed interface ImportReplaceRuleEffect {
    data object ImportFinished : ImportReplaceRuleEffect
}
