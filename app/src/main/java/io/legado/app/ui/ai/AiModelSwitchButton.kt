package io.legado.app.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.domain.gateway.AiProfileGateway
import io.legado.app.domain.model.AiTaskType
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.icon.AppIcon
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.koin.androidx.compose.koinViewModel

/**
 * AI 弹层通用的模型切换按钮（与 [AiReasoningModeButton] 同形态：tonal 图标按钮 + 点开选择弹层）。
 * 自带迷你 ViewModel，任何 AI 弹层一行接入；选择即改 CHAT 默认预设，
 * 各任务预设级联跟随（与对话页「切换模型与默认模型同步」同一入口）。
 */
@Composable
fun AiModelSwitchButton(
    enabled: Boolean = true,
    /** 选中新模型并落库成功后回调，调用方通常用它自动重试当前任务 */
    onSelected: (() -> Unit)? = null,
    viewModel: AiModelSwitchViewModel = koinViewModel(),
) {
    MediumTonalButton(
        onClick = viewModel::showPicker,
        icon = Icons.Default.AutoAwesome,
        enabled = enabled,
        contentDescription = stringResource(R.string.ai_select_model),
    )
    val sheetVisible by viewModel.sheetState.collectAsStateWithLifecycle()
    AiModelSwitchSheet(
        show = sheetVisible,
        onDismissRequest = viewModel::dismissPicker,
        onSelected = onSelected,
        viewModel = viewModel,
    )
}

/**
 * 模型选择弹层的独立形态，供想要自定义触发方式（如长按）的调用方使用；
 * [AiModelSwitchButton] 是"按钮 + 此弹层"的默认组合。
 */
@Composable
fun AiModelSwitchSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onSelected: (() -> Unit)? = null,
    viewModel: AiModelSwitchViewModel = koinViewModel(),
) {
    val models by viewModel.uiState.collectAsStateWithLifecycle()
    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.ai_select_model),
    ) {
        AiModelPickerContent(
            models = models,
            onSelect = { id -> viewModel.select(id) { onSelected?.invoke() } },
        )
    }
}

@Composable
private fun AiModelPickerContent(
    models: ImmutableList<AiModelOptionUi>,
    onSelect: (String) -> Unit,
) {
    if (models.isEmpty()) {
        androidx.compose.material3.Text(
            text = stringResource(R.string.ai_no_models_imported),
            style = LegadoTheme.typography.bodyMedium,
            color = LegadoTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(models, key = { it.modelProfileId }) { model ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(model.modelProfileId) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    androidx.compose.material3.Text(
                        text = model.modelName,
                        style = LegadoTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    androidx.compose.material3.Text(
                        text = model.providerName,
                        style = LegadoTheme.typography.labelSmall,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (model.isSelected) {
                    AppIcon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = LegadoTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

data class AiModelOptionUi(
    val modelProfileId: String,
    val providerName: String,
    val modelName: String,
    val isSelected: Boolean,
)

class AiModelSwitchViewModel(
    private val aiProfileGateway: AiProfileGateway,
) : ViewModel() {

    // 用 ImmutableList 作为流类型：combine 里 toImmutableList() 产出的是接口类型，
    // 声明成 PersistentList 会在赋值处不兼容
    private val _uiState = MutableStateFlow<ImmutableList<AiModelOptionUi>>(persistentListOf())
    val uiState = _uiState.asStateFlow()

    private val _sheetVisible = MutableStateFlow(false)
    val sheetState = _sheetVisible.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                aiProfileGateway.observeProviders(),
                aiProfileGateway.observeModels(),
                aiProfileGateway.observePresets(),
            ) { providers, models, presets ->
                val providerMap = providers.filter { it.enabled }.associateBy { it.id }
                val currentModelId = presets
                    .filter { it.taskType == AiTaskType.CHAT && it.enabled }
                    .let { chatPresets ->
                        chatPresets.firstOrNull { it.isDefault } ?: chatPresets.firstOrNull()
                    }
                    ?.modelProfileId
                models.filter { it.enabled && providerMap.containsKey(it.providerId) }
                    .map { model ->
                        AiModelOptionUi(
                            modelProfileId = model.id,
                            providerName = providerMap[model.providerId]?.name.orEmpty(),
                            modelName = model.displayName,
                            isSelected = model.id == currentModelId,
                        )
                    }
                    .toImmutableList()
            }.collect { items ->
                _uiState.value = items
            }
        }
    }

    fun showPicker() {
        _sheetVisible.value = true
    }

    fun dismissPicker() {
        _sheetVisible.value = false
    }

    fun select(modelProfileId: String, onSelected: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                aiProfileGateway.setTaskPresetModel(AiTaskType.CHAT, modelProfileId)
            }.onSuccess {
                _sheetVisible.value = false
                onSelected()
            }
        }
    }
}
