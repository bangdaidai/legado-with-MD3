package io.legado.app.ui.book.shareCard

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.legado.app.help.book.ShareCardHtmlRenderer
import io.legado.app.ui.about.MarkdownSheet
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
 * 分享模板管理页。
 *
 * 参考替换净化页面风格：顶部 TabRow 分组、卡片列表、分组管理弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareCardManageScreen(
    state: ShareCardManageUiState,
    onIntent: (ShareCardManageIntent) -> Unit,
    effects: Flow<ShareCardManageEffect>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    LaunchedEffect(Unit) {
        effects.collectLatest { effect ->
            when (effect) {
                is ShareCardManageEffect.ShowToast -> context.toastOnUi(effect.message)
            }
        }
    }

    // 分组管理弹窗
    GroupManageBottomSheet(
        show = state.showGroupManage,
        groups = state.groups,
        onDismissRequest = { onIntent(ShareCardManageIntent.DismissGroupManage) },
        onUpdateGroup = { old, new -> onIntent(ShareCardManageIntent.RenameGroup(old, new)) },
        onDeleteGroup = { onIntent(ShareCardManageIntent.DeleteGroup(it)) }
    )

    // 删除确认对话框
    AppAlertDialog(
        show = state.deleteConfirm != null,
        onDismissRequest = { onIntent(ShareCardManageIntent.DismissDelete) },
        title = "删除模板",
        text = "确定要删除模板「${state.deleteConfirm?.name ?: ""}」吗？",
        confirmText = "删除",
        onConfirm = { onIntent(ShareCardManageIntent.ConfirmDelete) },
        dismissText = "取消",
        onDismiss = { onIntent(ShareCardManageIntent.DismissDelete) }
    )

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "分享模板",
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                actions = {
                    TopBarActionButton(
                        onClick = { onIntent(ShareCardManageIntent.StartEdit(null)) },
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
                                    onIntent(ShareCardManageIntent.ShowGroupManage)
                                    showMenu = false
                                }
                            )
                            RoundDropdownMenuItem(
                                text = "恢复内置模板",
                                onClick = {
                                    onIntent(ShareCardManageIntent.RestoreBuiltins)
                                    showMenu = false
                                }
                            )
                            RoundDropdownMenuItem(
                                text = "帮助",
                                onClick = {
                                    onIntent(ShareCardManageIntent.ShowHelp)
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
                        onIntent(ShareCardManageIntent.SelectGroup(group))
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
                            onIntent(ShareCardManageIntent.SetDefault(template.id))
                        },
                        trailingAction = {
                            SmallPlainButton(
                                onClick = { onIntent(ShareCardManageIntent.ShowPreview(template)) },
                                icon = Icons.Default.Visibility,
                                contentDescription = "预览",
                            )
                            SmallPlainButton(
                                onClick = { onIntent(ShareCardManageIntent.StartEdit(template)) },
                                icon = AppIcons.Edit,
                                contentDescription = "编辑",
                            )
                            SmallPlainButton(
                                onClick = { onIntent(ShareCardManageIntent.RequestDelete(template)) },
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
        ShareCardEditSheet(
            editing = editing,
            groups = state.groups,
            onIntent = onIntent,
        )
    }

    // 帮助 Sheet（复用项目通用 MarkdownSheet）
    MarkdownSheet(
        show = state.showHelp,
        title = "模板编写说明",
        content = ShareCardHelpMarkdown,
        onDismissRequest = { onIntent(ShareCardManageIntent.DismissHelp) },
    )

    // 预览模板 Sheet（离屏出图，与分享卡片预览面板同一套方案）
    state.previewTemplate?.let { template ->
        var previewBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        var renderFailed by remember { mutableStateOf(false) }
        LaunchedEffect(template.id) {
            // WebView 已预热（与分享面板共用常驻实例），直接出图，无需等动画或现建
            ShareCardHtmlRenderer.warm(context)
            previewBitmap = null
            renderFailed = false
            val bmp = ShareCardHtmlRenderer.renderCustom(context, template.htmlContent, PreviewVariables)
            if (bmp != null) {
                previewBitmap = bmp
            } else {
                renderFailed = true
            }
        }
        AppModalBottomSheet(
            show = true,
            onDismissRequest = { onIntent(ShareCardManageIntent.DismissPreview) },
            title = "预览：${template.name.ifBlank { "未命名" }}",
        ) {
            val maxPreviewHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
            val bmp = previewBitmap
            when {
                bmp != null -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxPreviewHeight)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        androidx.compose.foundation.Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "模板预览",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                        )
                    }
                }
                else -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(maxPreviewHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (renderFailed) {
                            AppText("渲染失败")
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
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

/** 帮助内容（Markdown 格式，供 MarkdownSheet 渲染）。 */
private val ShareCardHelpMarkdown = """
模板使用双大括号占位符（如 `{{bookName}}`）来引用书籍数据，生成分享卡片时会自动替换为实际内容。

## 主题色（长按换色）
在分享卡片预览面板，**长按左上角切换模板按钮**可弹出色板：默认 / 8 种预设色 / 自定义取色。选中后**整套配色**（背景 / 表面 / 文字 / 强调色）会一起变。颜色仅本次预览生效，不写库、不修改模板。

模板要用变量引用颜色才能被换色。语法是 `var(--变量名, 兜底色)`——兜底色是没有选颜色时的默认色，兼容旧模板。

**可用变量（14 个）：**

主色相关：

| 变量 | 用途 | 建议兜底色 |
|---|---|---|
| `--bp-accent` | 主色（爱心 / 图标 / 进度条 / 实心装饰） | 你原来的强调色 |
| `--bp-accent-light` | 主色极浅版（背景渐变端、外框） | 你原来的浅强调色 |
| `--bp-accent-rgb` | 主色**裸三元组** `r,g,b` — 见下方「自由控透明度」 | — |
| `--bp-star` | 星级 / 评分色 | `#ffd700` |
| `--bp-on-accent` | 压在 `--bp-accent` 之上的文字色（自动取深/浅） | `#fff` |

背景与表面（三层明度恒定分离，永不撞色）：

| 变量 | 用途 | 建议兜底色 |
|---|---|---|
| `--bp-bg` | 整体背景（**纯色**，渐变请自己拼） | 原深色 |
| `--bp-surface` | 卡片本体 | `rgba(255,255,255,0.05)` |
| `--bp-surface-rgb` | 卡片色裸三元组（毛玻璃半透明卡片用） | — |
| `--bp-surface-variant` | 卡片内次级块（亮色凹陷 / 暗色凸起） | `rgba(255,255,255,0.08)` |

文字三档 + 分隔线：

| 变量 | 用途 | 建议兜底色 |
|---|---|---|
| `--bp-text` | 最深字：标题 / 数值 | `#fff` 或 `#e0e0e0` |
| `--bp-text-muted` | 中等字：标签 / badge | `#aaa` |
| `--bp-text-subtle` | 最浅字：作者 / label / 底栏 | `#888` 或 `#666` |
| `--bp-text-rgb` | 文字色裸三元组（投影 / 极低透明文字） | — |
| `--bp-divider` | 分隔线 / 边框（自带 alpha） | `rgba(255,255,255,0.1)` |

### 自由控透明度（`*-rgb` 三元组）

CSS 没法给一个 `var()` 改 alpha。所以额外提供三个**裸三元组**（值形如 `250,170,185`），配 `rgba()` 用，想要多少透明度都行：

```css
/* 极淡斜纹底 */
background: rgba(var(--bp-accent-rgb), 0.06);
/* 虚线边框 */
border: 1.5px dashed rgba(var(--bp-accent-rgb), 0.22);
/* 毛玻璃卡片 */
background: rgba(var(--bp-surface-rgb), 0.88);
/* 柔和投影 */
box-shadow: 0 4px 20px rgba(var(--bp-text-rgb), 0.08);
```

### 自己拼渐变

`--bp-bg` 是**纯色**，不再是渐变。想要渐变自己组合，灵活度更高：

```css
body {
  background:
    radial-gradient(ellipse at 10% 20%, rgba(var(--bp-accent-rgb), 0.12), transparent 55%),
    linear-gradient(145deg, var(--bp-surface), var(--bp-bg), var(--bp-accent-light));
}
```

### 亮 / 暗分支（`.bp-dark`）

暗色方案下，渲染器会给 `<html>` 加上 `class="bp-dark"`。**语义色**（比如「在读=绿、读完=蓝」这种不该跟着主色变的信息色）就用它翻色：

```css
.status-tag.在读 { background: #dbedd5; color: #41663b; }
.bp-dark .status-tag.在读 { background: #22331e; color: #a8d49a; }
```

> **`.bp-dark` 只用来翻颜色，不要写成亮暗两套排版。**
>
> 卡片高度是量一次存下来复用的（换色、切日夜都不重量），前提就是**明暗两态的布局完全一致**。
> 如果在 `.bp-dark` 里改了影响布局的属性，切日夜时会按另一套高度出图，表现为**底部缺一节或多一块空白**。
>
> 可以改：`color`、`background`、`background-color`、`border-color`、`box-shadow`、`text-shadow`、`opacity`、`fill`、`stroke`
>
> 不要改：`display`、`font-size`、`font-weight`、`letter-spacing`、`line-height`、`padding`、`margin`、`border-width`、`width`、`height`
>
> `font-weight` 尤其容易踩——它不改字号，但会让文字变宽，卡在折行边界上的标题加粗后会多折一行，高度就变了。

**改造旧模板：** 把 CSS 里写死的十六进制颜色改成 `var(...)` 形式即可。示例：

```css
/* 改前 */
.title { color: #fff; }
.section { color: #4a9eff; }
.card { background: #1a1a2e; border: 1px solid rgba(255,255,255,.1); }

/* 改后 */
.title { color: var(--bp-text, #fff); }
.section { color: var(--bp-accent, #4a9eff); }
.card { background: var(--bp-surface, #1a1a2e); border: 1px solid var(--bp-divider, rgba(255,255,255,.1)); }
```

改不改都行——不改的模板不会坏，只是长按选色对它没效果。想固定不参与换色的元素（比如封面图圆角、语义状态色）**保留原色**即可。

> 旧模板用过的 `--bp-accent-fade` 仍然会派生（主色 15% 透明版），向后兼容不用改。新模板推荐用 `rgba(var(--bp-accent-rgb), α)` 自选透明度，更灵活。

## 基本信息
| 字段 | 说明 |
|---|---|
| `{{bookName}}` | 书名 |
| `{{author}}` | 作者 |
| `{{coverUrl}}` | 封面图 URL |
| `{{intro}}` | 简介 |
| `{{kind}}` | 分类 |
| `{{wordCount}}` | 字数 |
| `{{originName}}` | 来源名称 |
| `{{totalChapterNum}}` | 总章节数 |
| `{{latestChapterTitle}}` | 最新章节标题 |
| `{{typeText}}` | 类型 |
| `{{charset}}` | 编码 |

## 阅读进度
| 字段 | 说明 |
|---|---|
| `{{readingStatusText}}` | 阅读状态 |
| `{{readingProgress}}` | 阅读进度（如 42%） |
| `{{readChapters}}` | 已读章节 |
| `{{unreadChapters}}` | 未读章节数 |
| `{{readIteration}}` | 重读次数 |
| `{{readIterationText}}` | 重读次数（文本） |
| `{{durChapterTitle}}` | 当前章节标题 |

## 阅读统计
| 字段 | 说明 |
|---|---|
| `{{totalReadTime}}` | 累计阅读时长 |
| `{{totalReadHours}}` | 累计小时 |
| `{{totalReadMinutes}}` | 累计分钟 |
| `{{readingDays}}` | 阅读天数 |
| `{{maxDayReadTime}}` | 单日最长阅读时长 |
| `{{maxDayReadDate}}` | 单日最长阅读日期 |
| `{{totalReadWords}}` | 累计已读字数 |
| `{{remainingWords}}` | 剩余字数 |

## 日期时间
| 字段 | 说明 |
|---|---|
| `{{firstReadTime}}` | 首次阅读时间 |
| `{{lastReadTime}}` | 最近阅读时间 |
| `{{finishReadTime}}` | 读完时间 |
| `{{addBookshelfTime}}` | 加入书架时间 |
| `{{lastCheckTime}}` | 最近检查更新时间 |
| `{{lastReadTimeRelative}}` | 最近阅读（相对时间） |

## 评分书评
| 字段 | 说明 |
|---|---|
| `{{rating}}` | 评分（数值） |
| `{{ratingStars}}` | 评分（星号） |
| `{{ratingMax}}` | 评分上限 |
| `{{reviewContent}}` | 书评内容 |

## 书摘想法
| 字段 | 说明 |
|---|---|
| `{{annotationCount}}` | 书摘总数 |
| `{{thoughtCount}}` | 想法总数 |
| `{{latestAnnotation}}` | 最新书摘 |
| `{{latestAnnotationNote}}` | 最新书摘备注 |
| `{{latestAnnotationChapter}}` | 最新书摘所在章节 |

## 其它
| 字段 | 说明 |
|---|---|
| `{{protagonists}}` | 主角 |
| `{{tags}}` | 标签 |
| `{{tagCount}}` | 标签数 |
| `{{bookSourceName}}` | 书源名称 |
| `{{bookSourceGroup}}` | 书源分组 |
| `{{readTimeRank}}` | 阅读时长排名 |

## 完整示例模板

下面是一份**简单但要素完整**的模板，可直接复制使用。它包含：海报根节点 `data-bp-capture`（渲染器据此裁图）、用 `--bp-*` 变量配色（可被换色 / 切日夜影响）、自己拼的渐变背景、封面（带加载失败兜底）、语义色 `.bp-dark` 亮暗分支。

```html
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    padding: 24px 18px;
    /* 渐变自己拼：--bp-bg 是纯色，用 --bp-accent-rgb 控透明度 */
    background:
      radial-gradient(circle at 15% 10%, rgba(var(--bp-accent-rgb, 255,255,255), 0.18), transparent 55%),
      linear-gradient(160deg, var(--bp-surface, #fff), var(--bp-bg, #eee));
    font-family: "Noto Sans SC", "PingFang SC", sans-serif;
    color: var(--bp-text, #333);
  }
  .card {
    max-width: 420px; margin: 0 auto;
    border-radius: 20px; padding: 20px;
    background: var(--bp-surface, #fff);
    border: 1px solid var(--bp-divider, rgba(0,0,0,.1));
    box-shadow: 0 8px 24px rgba(var(--bp-text-rgb, 0,0,0), 0.08);
  }
  .cover {
    width: 84px; height: 112px; border-radius: 10px; object-fit: cover;
    background: var(--bp-accent-light, #eee);
  }
  .name { font-size: 22px; font-weight: 700; color: var(--bp-text, #222); }
  .author { font-size: 13px; color: var(--bp-text-muted, #666); }
  .tag {
    display: inline-block; padding: 4px 12px; border-radius: 20px;
    background: var(--bp-accent, #ccc); color: var(--bp-on-accent, #fff);
  }
  /* 语义色按亮暗翻色（.bp-dark 由渲染器在暗色方案下加到 <html>） */
  .tag.在读 { background: #d8efd2; color: #3c6236; }
  .bp-dark .tag.在读 { background: #2c4227; color: #b6d9ac; }
  .meta { font-size: 14px; color: var(--bp-text-subtle, #888); margin-top: 6px; }
</style>
</head>
<body>
  <!-- 海报根节点必须标 data-bp-capture，渲染器据此裁图 -->
  <div class="card" data-bp-capture>
    <img class="cover" src="{{coverUrl}}" alt="封面" onerror="this.style.display='none'">
    <div class="name">{{bookName}}</div>
    <div class="author">{{author}}</div>
    <span class="tag {{readingStatusText}}">{{readingStatusText}}</span>
    <div class="meta">{{readingProgress}} · {{totalReadTime}}</div>
  </div>
</body>
</html>
```
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareCardEditSheet(
    editing: io.legado.app.data.entities.ShareCardTemplate,
    groups: List<String>,
    onIntent: (ShareCardManageIntent) -> Unit,
) {
    var name by remember(editing.id) { mutableStateOf(editing.name) }
    var html by remember(editing.id) { mutableStateOf(editing.htmlContent) }
    var group by remember(editing.id) { mutableStateOf(editing.groupName) }

    AppModalBottomSheet(
        show = true,
        onDismissRequest = { onIntent(ShareCardManageIntent.CancelEdit) },
        title = if (editing.id == 0L) "新建模板" else "编辑模板",
        endAction = {
            MediumTonalButton(
                onClick = { onIntent(ShareCardManageIntent.SaveTemplate(name, html, group)) },
                icon = AppIcons.Check,
                contentDescription = "保存",
            )
        },
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
