package io.legado.app.ui.book.shareCard

import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

    // 预览模板 Sheet（实时 WebView，与分享卡片预览面板同一套方案）
    state.previewTemplate?.let { template ->
        // 注意：这里不能用 remember(template.id)。template.id 变化时 remember 会重算并把
        // previewWebView 复位成 null，但 AndroidView 的 factory 不会因 template.id 变化重建，
        // 于是 previewWebView 永远拿不到新实例、LaunchedEffect 里 previewWebView?:return 永久返回，
        // 切换模板后就会一直转圈。改用无 key 的 remember，切模板时复用同一个 WebView 重新 loadData。
        var previewWebView by remember { mutableStateOf<WebView?>(null) }
        var renderFailed by remember { mutableStateOf(false) }
        // 由 View.measure(UNSPECIFIED) 量出的 WebView 内容真实高度(dp)。固定高度避免 WRAP_CONTENT 反复重测抖动（卡顿）。
        var contentHeightDp by remember { mutableStateOf<Float?>(null) }
        // 高度稳定后才展示 WebView，避免容器先矮、资源落定后再变高导致的“两段跳”。
        var contentStable by remember { mutableStateOf(false) }
        // 布局监听 + 悬挂延时 token：必须在 factory/onRelease 之外用 remember 持有，
        // 否则 onRelease 拿不到（作用域只在 factory 的 apply 块内），会导致 Unresolved reference 编译失败。
        // 切模板重加载时移除旧监听 + 取消旧 token，避免误触发。
        var pendingToken by remember { mutableStateOf<Runnable?>(null) }
        var activeListener by remember { mutableStateOf<ViewTreeObserver.OnGlobalLayoutListener?>(null) }
        LaunchedEffect(template.id, previewWebView) {
            val wv = previewWebView ?: return@LaunchedEffect
            renderFailed = false
            contentHeightDp = null
            contentStable = false
            val html = ShareCardHtmlRenderer.buildCustomPreviewHtml(
                template.htmlContent, PreviewVariables,
            )
            if (html.isBlank()) {
                renderFailed = true
                return@LaunchedEffect
            }
            wv.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
        }
        AppModalBottomSheet(
            show = true,
            onDismissRequest = { onIntent(ShareCardManageIntent.DismissPreview) },
            title = "预览：${template.name.ifBlank { "未命名" }}",
        ) {
            // 宽度交给 WebView 自身钉死（useWideViewPort=false → 1 CSS px == 1 dp），
            // 高度用 measure(UNSPECIFIED) 量出的 contentHeightDp 固定（矮图矮、高图交给外层 verticalScroll 滚动）。
            // 配置与书籍页分享预览（ShareCardPreviewSheet）一致，避免误缩放成一小块、也避免固定高度矮图留白。
            val maxPreviewHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxPreviewHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    // WebView 必须无条件挂载，否则实例建不起来、HTML 无从加载
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    // 宽度交给控件 MATCH_PARENT / EXACTLY，viewport meta 一律忽略，
                                    // 保证 1 CSS px == 1 dp，模板按控件宽度渲染、不横滑、不缩放。
                                    useWideViewPort = false
                                    loadWithOverviewMode = false
                                    setSupportZoom(false)
                                    builtInZoomControls = false
                                    blockNetworkLoads = false
                                    blockNetworkImage = false
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                }
                                // WebView 自身不滚动：高度已等于内容高度，滚动交给外层 Compose
                                isVerticalScrollBarEnabled = false
                                overScrollMode = WebView.OVER_SCROLL_NEVER
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                // 切模板重加载时，移除上一次可能残留的布局监听 + 取消悬挂的延时 token，避免旧监听/旧 token 误触发。
                                // activeListener / pendingToken 在外层 remember 持有（见 contentStable 下方），此处直接复用。
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        val v = view ?: return
                                        activeListener?.let { v.viewTreeObserver.removeOnGlobalLayoutListener(it) }
                                        pendingToken?.let { v.removeCallbacks(it) }
                                        // UNSPECIFIED 不限高量内容高度：能缩能长（修复长模板切短模板高度不缩、残留背景）。
                                        // 封面盒固定尺寸、图片加载不改高度（有/无封面高度一致）；
                                        // 这里用布局监听 + 150ms 稳定判定主要兜字体/异步资源落定，等不再变化才展示，避免两段跳。
                                        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
                                            private var lastH = -1
                                            override fun onGlobalLayout() {
                                                val self = this
                                                val width = v.width
                                                if (width <= 0) return
                                                v.measure(
                                                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                                                )
                                                val h = v.measuredHeight
                                                if (h <= 0) return
                                                if (h != lastH) {
                                                    lastH = h
                                                    // 高度还在变：推迟 150ms 再判定（期间若又变则本次 onGlobalLayout 自然再推）。
                                                    pendingToken?.let { v.removeCallbacks(it) }
                                                    pendingToken = Runnable {
                                                        pendingToken = null
                                                        if (!v.isAttachedToWindow) return@Runnable
                                                        val density = v.resources.displayMetrics.density
                                                        // 先移除监听，再提交高度：否则下面设高度会触发新的 onGlobalLayout，
                                                        // 若取整差 1px 又会取消本 token 重排，造成反复抖动。
                                                        v.viewTreeObserver.removeOnGlobalLayoutListener(self)
                                                        contentHeightDp = h.toFloat() / density
                                                        contentStable = true
                                                    }
                                                    v.postDelayed(pendingToken, 150L)
                                                }
                                            }
                                        }
                                        activeListener = listener
                                        listener.onGlobalLayout()
                                        v.viewTreeObserver.addOnGlobalLayoutListener(listener)
                                    }
                                }
                                previewWebView = this
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((contentHeightDp ?: 240f).dp),
                        onRelease = {
                            pendingToken?.let { tk -> it.removeCallbacks(tk) }
                            pendingToken = null
                            activeListener?.let { lst -> it.viewTreeObserver.removeOnGlobalLayoutListener(lst) }
                            activeListener = null
                            it.stopLoading()
                            it.destroy()
                            previewWebView = null
                        },
                    )
                    if (renderFailed) {
                        AppText("渲染失败")
                    } else if (!contentStable) {
                        CircularProgressIndicator()
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
