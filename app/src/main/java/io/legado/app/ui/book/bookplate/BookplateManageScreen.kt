package io.legado.app.ui.book.bookplate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

/**
 * 藏书票模板管理页。
 *
 * 单击卡片选中模板（生成藏书票时使用），长按卡片进入编辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookplateManageScreen(
    state: BookplateManageUiState,
    onIntent: (BookplateManageIntent) -> Unit,
    effects: Flow<BookplateManageEffect>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    LaunchedEffect(Unit) {
        effects.collectLatest { effect ->
            when (effect) {
                is BookplateManageEffect.ShowToast -> context.toastOnUi(effect.message)
            }
        }
    }

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "藏书票模板",
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                actions = {
                    TopBarActionButton(
                        onClick = { onIntent(BookplateManageIntent.StartEdit(null)) },
                        imageVector = Icons.Default.Add,
                        contentDescription = "新建",
                    )
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.groups) { group ->
                    FilterChip(
                        selected = group == state.selectedGroup,
                        onClick = { onIntent(BookplateManageIntent.SelectGroup(group)) },
                        label = { AppText(group) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.templates, key = { it.id }) { template ->
                    NormalCard(
                        onClick = { onIntent(BookplateManageIntent.SelectTemplate(template.id)) },
                        onLongClick = { onIntent(BookplateManageIntent.StartEdit(template)) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = template.id == state.selectedTemplateId,
                                onClick = {
                                    onIntent(BookplateManageIntent.SelectTemplate(template.id))
                                },
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                AppText(
                                    text = template.name.ifBlank { "未命名" },
                                    style = LegadoTheme.typography.titleSmall,
                                )
                                if (template.isBuiltin) {
                                    AppText(
                                        text = "内置",
                                        style = LegadoTheme.typography.labelSmall,
                                        color = LegadoTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    state.editing?.let { editing ->
        BookplateEditSheet(
            editing = editing,
            onIntent = onIntent,
        )
    }
}

@Composable
private fun BookplateEditSheet(
    editing: io.legado.app.data.entities.BookplateTemplate,
    onIntent: (BookplateManageIntent) -> Unit,
) {
    var name by remember(editing.id) { mutableStateOf(editing.name) }
    var html by remember(editing.id) { mutableStateOf(editing.htmlContent) }

    AppModalBottomSheet(
        show = true,
        onDismissRequest = { onIntent(BookplateManageIntent.CancelEdit) },
        title = if (editing.id == 0L) "新建模板" else "编辑模板",
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = "模板名称",
                singleLine = true,
            )
            AppTextField(
                value = html,
                onValueChange = { html = it },
                modifier = Modifier.fillMaxWidth(),
                label = "HTML 内容",
                minLines = 4,
                maxLines = 8,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(BookplateVariables) { variable ->
                    FilterChip(
                        selected = false,
                        onClick = { html += variable },
                        label = {
                            AppText(variable, style = LegadoTheme.typography.labelSmall)
                        },
                    )
                }
            }
            MediumTonalButton(
                onClick = { onIntent(BookplateManageIntent.SaveTemplate(name, html)) },
                modifier = Modifier.fillMaxWidth(),
                text = "保存",
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 模板中可用的占位变量，与 [io.legado.app.help.book.BookplateHtmlRenderer] 支持的键保持一致。 */
private val BookplateVariables = listOf(
    "{{bookName}}",
    "{{author}}",
    "{{coverUrl}}",
    "{{ratingStars}}",
    "{{readingStatusText}}",
    "{{readingProgress}}",
    "{{totalReadTime}}",
    "{{readingDays}}",
    "{{firstReadTime}}",
    "{{lastReadTime}}",
    "{{reviewContent}}",
)
