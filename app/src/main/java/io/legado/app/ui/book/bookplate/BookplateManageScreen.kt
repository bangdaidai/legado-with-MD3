package io.legado.app.ui.book.bookplate

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.legado.app.help.book.BookplateHtmlRenderer
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.GroupManageBottomSheet
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.card.SelectionItemCard
import io.legado.app.ui.widget.components.icon.AppIcons
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.tabRow.AppTabRow
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
 * 参考替换净化页面风格：顶部 TabRow 分组、卡片列表、分组管理弹窗。
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

    // 分组管理弹窗
    GroupManageBottomSheet(
        show = state.showGroupManage,
        groups = state.groups,
        onDismissRequest = { onIntent(BookplateManageIntent.DismissGroupManage) },
        onUpdateGroup = { old, new -> onIntent(BookplateManageIntent.RenameGroup(old, new)) },
        onDeleteGroup = { onIntent(BookplateManageIntent.DeleteGroup(it)) }
    )

    // 删除确认对话框
    AppAlertDialog(
        show = state.deleteConfirm != null,
        onDismissRequest = { onIntent(BookplateManageIntent.DismissDelete) },
        title = "删除模板",
        text = "确定要删除模板「${state.deleteConfirm?.name ?: ""}」吗？",
        confirmText = "删除",
        onConfirm = { onIntent(BookplateManageIntent.ConfirmDelete) },
        dismissText = "取消",
        onDismiss = { onIntent(BookplateManageIntent.DismissDelete) }
    )

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
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        TopBarActionButton(
                            onClick = { showMenu = true },
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "更多",
                        )
                        RoundDropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            RoundDropdownMenuItem(
                                text = "分组管理",
                                onClick = {
                                    onIntent(BookplateManageIntent.ShowGroupManage)
                                    showMenu = false
                                }
                            )
                            RoundDropdownMenuItem(
                                text = "恢复内置模板",
                                onClick = {
                                    onIntent(BookplateManageIntent.RestoreBuiltins)
                                    showMenu = false
                                }
                            )
                            RoundDropdownMenuItem(
                                text = "帮助",
                                onClick = {
                                    onIntent(BookplateManageIntent.ShowHelp)
                                    showMenu = false
                                }
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // Tab 分组切换（参考替换净化）
            val tabItems = remember(state.groups) { listOf("全部") + state.groups }
            val selectedTabIndex = state.selectedGroup
                ?.let(tabItems::indexOf)
                ?.takeIf { it >= 0 }
                ?: 0

            if (tabItems.size > 1) {
                AppTabRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    tabTitles = tabItems,
                    selectedTabIndex = selectedTabIndex,
                    onTabSelected = { index ->
                        val group = if (index == 0) null else tabItems[index]
                        onIntent(BookplateManageIntent.SelectGroup(group))
                    }
                )
            }

            // 模板列表（参考替换净化卡片风格）
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.templates, key = { it.id }) { template ->
                    SelectionItemCard(
                        title = template.name.ifBlank { "未命名" },
                        subtitle = buildString {
                            if (template.groupName.isNotBlank()) append(template.groupName)
                            if (template.isBuiltin) {
                                if (isNotEmpty()) append(" · ")
                                append("内置")
                            }
                            if (template.id == state.defaultTemplateId) {
                                if (isNotEmpty()) append(" · ")
                                append("默认")
                            }
                        }.ifBlank { null },
                        isSelected = template.id == state.defaultTemplateId,
                        onToggleSelection = {
                            onIntent(BookplateManageIntent.SetDefault(template.id))
                        },
                        trailingAction = {
                            SmallPlainButton(
                                onClick = { onIntent(BookplateManageIntent.ShowPreview(template)) },
                                icon = Icons.Default.Visibility,
                                contentDescription = "预览",
                            )
                            SmallPlainButton(
                                onClick = { onIntent(BookplateManageIntent.StartEdit(template)) },
                                icon = AppIcons.Edit,
                                contentDescription = "编辑",
                            )
                            SmallPlainButton(
                                onClick = { onIntent(BookplateManageIntent.RequestDelete(template)) },
                                icon = AppIcons.Delete,
                                contentDescription = "删除",
                            )
                        }
                    )
                }
            }
        }
    }

    // 编辑模板 Sheet
    state.editing?.let { editing ->
        BookplateEditSheet(
            editing = editing,
            groups = state.groups,
            onIntent = onIntent,
        )
    }

    // 帮助 Sheet
    if (state.showHelp) {
        BookplateHelpSheet(
            onDismissRequest = { onIntent(BookplateManageIntent.DismissHelp) },
        )
    }

    // 预览模板 Sheet（真实渲染为图片）
    state.previewTemplate?.let { template ->
        var bitmap by remember(template.id) { mutableStateOf<Bitmap?>(null) }
        var rendering by remember(template.id) { mutableStateOf(true) }
        LaunchedEffect(template.id) {
            rendering = true
            bitmap = BookplateHtmlRenderer.renderCustom(context, template.htmlContent, PreviewVariables)
            rendering = false
        }
        AppModalBottomSheet(
            show = true,
            onDismissRequest = { onIntent(BookplateManageIntent.DismissPreview) },
            title = "预览：${template.name.ifBlank { "未命名" }}",
        ) {
            when {
                rendering -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
                bitmap != null -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> AppText(
                    text = "渲染失败",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 预览用示例变量。 */
private val PreviewVariables = mapOf(
    "bookName" to "示例书名",
    "author" to "示例作者",
    "coverUrl" to "",
    "rating" to "4.5",
    "ratingStars" to "★★★★☆",
    "readingStatusText" to "在读",
    "readingProgress" to "42%",
    "totalReadTime" to "12小时30分",
    "readingDays" to "15",
    "firstReadTime" to "2026-01-01",
    "lastReadTime" to "2026-08-02",
    "reviewContent" to "这是一段示例书评，用于预览模板效果。",
    "intro" to "这是一本示例书籍的简介。",
    "kind" to "示例分类",
    "wordCount" to "10万字",
)

/** 模板可用字段说明（分组 → 字段列表）。 */
private val HelpFieldGroups = listOf(
    "基本信息" to listOf(
        "bookName" to "书名",
        "author" to "作者",
        "coverUrl" to "封面图 URL",
        "intro" to "简介",
        "kind" to "分类",
        "wordCount" to "字数",
        "originName" to "书源名称",
        "totalChapterNum" to "总章节数",
        "latestChapterTitle" to "最新章节标题",
        "typeText" to "类型",
        "charset" to "编码",
    ),
    "阅读进度" to listOf(
        "readingStatusText" to "阅读状态",
        "readingProgress" to "阅读进度（如 42%）",
        "readChapters" to "已读章节",
        "unreadChapters" to "未读章节数",
        "readIteration" to "重读次数",
        "readIterationText" to "重读次数（文本）",
        "durChapterTitle" to "当前章节标题",
    ),
    "阅读统计" to listOf(
        "totalReadTime" to "累计阅读时长",
        "totalReadHours" to "累计小时",
        "totalReadMinutes" to "累计分钟",
        "readingDays" to "阅读天数",
        "maxDayReadTime" to "单日最长阅读时长",
        "maxDayReadDate" to "单日最长阅读日期",
        "totalReadWords" to "累计已读字数",
        "remainingWords" to "剩余字数",
    ),
    "日期时间" to listOf(
        "firstReadTime" to "首次阅读时间",
        "lastReadTime" to "最近阅读时间",
        "finishReadTime" to "读完时间",
        "addBookshelfTime" to "加入书架时间",
        "lastCheckTime" to "最近检查更新时间",
        "lastReadTimeRelative" to "最近阅读（相对时间）",
    ),
    "评分书评" to listOf(
        "rating" to "评分（数值）",
        "ratingStars" to "评分（星号）",
        "ratingMax" to "评分上限",
        "reviewContent" to "书评内容",
    ),
    "书摘想法" to listOf(
        "annotationCount" to "书摘总数",
        "thoughtCount" to "想法总数",
        "latestAnnotation" to "最新书摘",
        "latestAnnotationNote" to "最新书摘备注",
        "latestAnnotationChapter" to "最新书摘所在章节",
    ),
    "其它" to listOf(
        "protagonists" to "主角",
        "tags" to "标签",
        "tagCount" to "标签数",
        "bookSourceName" to "书源名称",
        "bookSourceGroup" to "书源分组",
        "readTimeRank" to "阅读时长排名",
    ),
)

@Composable
private fun BookplateHelpSheet(
    onDismissRequest: () -> Unit,
) {
    AppModalBottomSheet(
        show = true,
        onDismissRequest = onDismissRequest,
        title = "模板可用字段",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppText(
                text = "模板使用双大括号占位符（如 {{bookName}}）来引用书籍数据，" +
                    "生成藏书票时会自动替换为实际内容。以下是全部支持的字段：",
                style = LegadoTheme.typography.bodySmall,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
            )
            HelpFieldGroups.forEach { (groupTitle, items) ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppText(
                        text = groupTitle,
                        style = LegadoTheme.typography.titleSmall,
                        color = LegadoTheme.colorScheme.primary,
                    )
                    items.forEach { (key, desc) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AppText(
                                text = "{{$key}}",
                                style = LegadoTheme.typography.bodyMedium,
                                color = LegadoTheme.colorScheme.primary,
                            )
                            AppText(
                                text = desc,
                                style = LegadoTheme.typography.bodyMedium,
                                color = LegadoTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookplateEditSheet(
    editing: io.legado.app.data.entities.BookplateTemplate,
    groups: List<String>,
    onIntent: (BookplateManageIntent) -> Unit,
) {
    var name by remember(editing.id) { mutableStateOf(editing.name) }
    var html by remember(editing.id) { mutableStateOf(editing.htmlContent) }
    var group by remember(editing.id) { mutableStateOf(editing.groupName) }

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

            // 分组选择（参考添加标签对话框的 GroupField）
            GroupSelector(
                groups = groups,
                selectedGroup = group,
                onSelect = { group = it },
            )

            AppTextField(
                value = html,
                onValueChange = { html = it },
                modifier = Modifier.fillMaxWidth(),
                label = "HTML 内容",
                minLines = 4,
                maxLines = 8,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (editing.id != 0L) {
                    MediumTonalButton(
                        onClick = { onIntent(BookplateManageIntent.RequestDelete(editing)) },
                        modifier = Modifier.weight(1f),
                        text = "删除",
                    )
                }
                MediumTonalButton(
                    onClick = { onIntent(BookplateManageIntent.SaveTemplate(name, html, group)) },
                    modifier = Modifier.weight(1f),
                    text = "保存",
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupSelector(
    groups: List<String>,
    selectedGroup: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        AppTextField(
            value = selectedGroup,
            onValueChange = onSelect,
            label = "分组",
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, true),
        )
        if (groups.isNotEmpty()) {
            RoundDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                groups.forEach { g ->
                    RoundDropdownMenuItem(
                        text = g,
                        onClick = { onSelect(g); expanded = false },
                    )
                }
            }
        }
    }
}
