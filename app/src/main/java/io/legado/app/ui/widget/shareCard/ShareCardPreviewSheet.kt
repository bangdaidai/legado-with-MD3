package io.legado.app.ui.widget.shareCard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.data.entities.ShareCardData
import io.legado.app.data.entities.ShareCardTemplate
import io.legado.app.data.repository.ShareCardRepository
import io.legado.app.help.book.ShareCardGenerator
import io.legado.app.help.book.ShareCardHtmlRenderer
import io.legado.app.help.config.AppConfigStore
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.dialog.ColorPickerSheet
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** 长按换色可选的预设主题色，最后一项走取色器自定义 */
private val ACCENT_PRESETS = listOf(
    "薄荷绿" to 0xFF6BCBB1.toInt(),
    "樱花粉" to 0xFFF4A9B6.toInt(),
    "海蓝色" to 0xFF5BA3D9.toInt(),
    "奶油黄" to 0xFFE6C97A.toInt(),
    "雾霭紫" to 0xFF9B8BC0.toInt(),
    "石墨灰" to 0xFF6B737D.toInt(),
)

@Composable
fun ShareCardPreviewSheet(
    show: Boolean,
    data: ShareCardData?,
    loading: Boolean,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val shareCardRepository = koinInject<ShareCardRepository>()

    var templates by remember { mutableStateOf<List<ShareCardTemplate>>(emptyList()) }
    var selectedTemplateId by remember { mutableLongStateOf(0L) }
    var showTemplateMenu by remember { mutableStateOf(false) }
    // 临时主题色：null = 用模板自身配色，不写库、关闭后不保留
    var accentColor by remember { mutableStateOf<Int?>(null) }
    var showPaletteMenu by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    // 方案覆盖：null=自动（按所选色明度判定），false=强制亮，true=强制暗。
    var schemeOverride by remember { mutableStateOf<Boolean?>(null) }

    // 实时预览 WebView 实例和初始化 HTML（加载一次，换色只注入 JS）
    var previewWebView by remember { mutableStateOf<WebView?>(null) }
    var htmlReady by remember { mutableStateOf(false) }
    // 模板缺失 / HTML 渲染为空时置位，避免菊花无限转
    var previewFailed by remember { mutableStateOf(false) }
    // 由 View.measure(UNSPECIFIED) 量出的 WebView 内容真实高度(dp)。
    // 必须用 UNSPECIFIED：WebView 被 MATCH_PARENT 钉在高容器里时，contentHeight / scrollHeight 量到的是
    // "视口高度"而非内容高度，内容比框矮时永远不往下缩（长模板切短模板残留大片背景）；
    // UNSPECIFIED 是"别限高、按内容量"，所以既能缩也能长。
    // 注意：封面是固定尺寸盒子（如 88×118、object-fit:cover），图片加载与否都不改盒子大小，
    // 因此封面图异步加载【不会】撑高/改变高度——有封面和没封面的书高度一致（无封面只是占位）。
    // 量高放在 onPageFinished（此时 wv.width 已就绪，不会因 width==0 退回兜底），
    // 再等布局稳定后才展示，主要是为了在 MATCH_PARENT 下拿到正确内容高度、并兜住字体/其它异步资源落定，
    // 避免"先矮后高"两段跳。量不出只用小兜底、不撑屏。
    var contentCssHeight by remember { mutableStateOf<Float?>(null) }
    // 内容高度是否已稳定：封面是固定盒、不随图片加载变化；这里主要兜字体/其它异步资源落定，
    // 稳定后才把 WebView 亮出来，避免容器先矮、资源落定后再变高导致的"二段"跳变。
    var contentStable by remember { mutableStateOf(false) }
    // 布局监听 + 悬挂延时 token：必须在 factory/onRelease 之外用 remember 持有，
    // 否则 onRelease 拿不到（作用域只在 factory 的 apply 块内），会导致 Unresolved reference 编译失败。
    // 切模板重加载时移除旧监听 + 取消旧 token，避免误触发。
    var pendingToken by remember { mutableStateOf<Runnable?>(null) }
    var activeListener by remember { mutableStateOf<ViewTreeObserver.OnGlobalLayoutListener?>(null) }
    // 长按保存：置位后由下面的 LaunchedEffect 直接截当前预览 WebView
    var saveRequested by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(show) {
        if (show) {
            accentColor = null
            schemeOverride = null
            htmlReady = false
            previewFailed = false
            contentCssHeight = null
            contentStable = false
            saveRequested = false
            saving = false
            val loaded = withContext(Dispatchers.IO) {
                ShareCardGenerator.getOrCreateBuiltinTemplates()
                shareCardRepository.getAll()
            }
            templates = loaded
            val saved = withContext(Dispatchers.IO) {
                AppConfigStore.getLong(ShareCardGenerator.SELECTED_TEMPLATE_KEY) ?: 0L
            }
            selectedTemplateId = loaded.firstOrNull { it.id == saved }?.id
                ?: loaded.firstOrNull {
                    it.isBuiltin && it.groupName == ShareCardTemplate.DEFAULT_GROUP_BOOK
                }?.id
                ?: loaded.firstOrNull()?.id
                ?: 0L
        }
    }

    // 切换模板时复位临时配色：注入的 #accent-style 标签是跨模板持久残留的，
    // 不在这里清掉的话，新模板加载完仍会被换色 effect 重新染上上一个模板的颜色。
    // 复位后新模板用自身默认色起手，用户再手动切色才会注入。
    LaunchedEffect(show, selectedTemplateId) {
        if (show && selectedTemplateId != 0L) {
            accentColor = null
            schemeOverride = null
        }
    }

    // WebView 就位或模板切换时加载 HTML（onPageFinished 里翻转 htmlReady 让菊花消失）
    // 注意 key 必须含 data：data 由调用方 ViewModel 异步产出（shareCardData 先 null 后非 null），
    // 若不放进 key，data 就位时本 effect 不会重跑，HTML 永远不加载，htmlReady 永远 false，
    // 菊花就一直转（有时 data 比 selectedTemplateId 早到则碰巧正常，时序不定表现为"偶尔卡死"）。
    LaunchedEffect(show, selectedTemplateId, previewWebView, data) {
        if (!show || data == null || selectedTemplateId == 0L) return@LaunchedEffect
        val wv = previewWebView ?: return@LaunchedEffect
        val tpl = templates.firstOrNull { it.id == selectedTemplateId } ?: return@LaunchedEffect
        htmlReady = false
        previewFailed = false
        contentCssHeight = null
        contentStable = false
        val html = ShareCardHtmlRenderer.buildPreviewHtml(tpl, data)
        if (html.isBlank()) {
            previewFailed = true
            return@LaunchedEffect
        }
        withContext(Dispatchers.Main) {
            wv.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
        }
    }

    // 量高在 onPageFinished 里用 View.measure(UNSPECIFIED) 直接量（见下方 webViewClient）。
    // UNSPECIFIED 不限高，故既能缩也能长（修复长模板切短模板高度不缩、残留大片背景）。
    // 封面盒固定尺寸，图片加载不改变高度；这里的布局监听 + 150ms 稳定判定
    // 主要兜字体/异步资源落定，等高度不再变化才展示 WebView（contentStable=true），避免"二段"跳变。

    // 换色 / 切亮暗 / 页面就绪：只注入一段 JS 改 style 标签内容，页面原地重绘。
    // 这是"瞬间切换"的关键——不 reload、不重建 WebView、不出图。换色不改高度，无需重测。
    LaunchedEffect(htmlReady, accentColor, schemeOverride) {
        if (!htmlReady) return@LaunchedEffect
        val wv = previewWebView ?: return@LaunchedEffect
        wv.evaluateJavascript(ShareCardHtmlRenderer.accentApplyJs(accentColor, schemeOverride), null)
    }

    // 换色 / 切亮暗只需改 accentColor / schemeOverride 状态，
    // 由上面的 LaunchedEffect 统一注入 JS，无需在各处手动触发。

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = "分享卡片",
        startAction = {
            Box {
                MediumTonalButton(
                    onClick = { showTemplateMenu = true },
                    onLongClick = { if (templates.isNotEmpty() && !loading) showPaletteMenu = true },
                    enabled = templates.isNotEmpty() && !loading,
                    icon = Icons.Default.SwapHoriz,
                    contentDescription = "切换模板（长按换色）",
                )
                RoundDropdownMenu(
                    expanded = showTemplateMenu,
                    onDismissRequest = { showTemplateMenu = false },
                ) { dismiss ->
                    templates.forEach { tpl ->
                        RoundDropdownMenuItem(
                            text = tpl.name.ifBlank { "未命名" },
                            isSelected = tpl.id == selectedTemplateId,
                            onClick = {
                                dismiss()
                                if (tpl.id == selectedTemplateId) return@RoundDropdownMenuItem
                                selectedTemplateId = tpl.id
                                // HTML 重新加载由 LaunchedEffect(selectedTemplateId) 驱动
                            },
                        )
                    }
                }
                RoundDropdownMenu(
                    expanded = showPaletteMenu,
                    onDismissRequest = { showPaletteMenu = false },
                ) { dismiss ->
                    RoundDropdownMenuItem(
                        text = "默认（模板自带）",
                        isSelected = accentColor == null,
                        onClick = {
                            dismiss()
                            if (accentColor == null) return@RoundDropdownMenuItem
                            accentColor = null
                            schemeOverride = null
                        },
                    )
                    ACCENT_PRESETS.forEach { (label, argb) ->
                        RoundDropdownMenuItem(
                            text = label,
                            isSelected = accentColor == argb,
                            leadingIcon = { ColorSwatch(argb) },
                            onClick = {
                                dismiss()
                                if (accentColor == argb) return@RoundDropdownMenuItem
                                accentColor = argb
                            },
                        )
                    }
                    RoundDropdownMenuItem(
                        text = "自定义…",
                        isSelected = accentColor != null && ACCENT_PRESETS.none { it.second == accentColor },
                        onClick = {
                            dismiss()
                            showColorPicker = true
                        },
                    )
                }
            }
        },
        endAction = {
            val accent = accentColor
            val effectiveDark = if (accent == null) {
                null
            } else {
                schemeOverride ?: ShareCardHtmlRenderer.isDarkByDefault(accent)
            }
            MediumTonalButton(
                onClick = {
                    val next = effectiveDark != true
                    schemeOverride = next
                },
                enabled = accentColor != null && !loading,
                selected = schemeOverride != null,
                icon = if (effectiveDark == true) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = "切换亮/暗（当前${if (effectiveDark == true) "暗色" else "亮色"}）",
            )
        },
    ) {
        // 宽度由 WebView 自身钉死（useWideViewPort=false → layout viewport == 控件宽度），
        // 高度用 measure(UNSPECIFIED) 量出的 contentCssHeight 决定：矮图矮、高图高；量不出只用小兜底、不撑屏。
        // WebView 用 MATCH_PARENT 填满内层固定高度的 Box（不靠 WRAP_CONTENT），避免资源异步加载
        // 后反复 requestLayout 的布局抖动（卡顿）；固定高度由 Compose 侧定死，Web 框不抖。
        // 关键：WebView 必须在弹窗一打开就无条件挂载（不能被 data 门控），让 WebView 的创建开销
        // 与入场动画重叠；否则会先弹一个小加载圈、等数据 IO 完才挂载 WebView 并撑高到全高，
        // 二次撑高落在入场动画里就成了"先出来一点、卡顿、再整个弹出"。
        val maxPreviewHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        // 内容高(dp)：measure(UNSPECIFIED) 量出的真实值（原始 px / density == dp）；量不出用小兜底(240dp)，
        // 绝不兜底整屏（否则矮图被撑出大段背景）。
        val contentDp = if (contentCssHeight != null && contentCssHeight!! > 0f) {
            contentCssHeight!!.dp
        } else {
            240.dp
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxPreviewHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(contentDp),
                contentAlignment = Alignment.Center,
            ) {
                // 实时 WebView 预览：HTML 只加载一次，换色靠 JS 注入原地重绘。
                // 无条件挂载（不能被 htmlReady / data 门控），否则 WebView 建不起来、HTML 无从加载。
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
                                // 保证 1 CSS px == 1 dp，模板按控件宽度渲染、不横滑、不留白。
                                useWideViewPort = false
                                loadWithOverviewMode = false
                                setSupportZoom(false)
                                builtInZoomControls = false
                                blockNetworkLoads = false
                                blockNetworkImage = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            }
                            // WebView 自身不滚动：高度已经等于内容高度，滚动交给外层 Compose，
                            // 否则截图时 draw() 会带上滚动偏移，出图和预览错位。
                            isVerticalScrollBarEnabled = false
                            overScrollMode = WebView.OVER_SCROLL_NEVER
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            // 切模板重加载时，移除上一次可能残留的布局监听 + 取消悬挂的延时 token，避免旧监听/旧 token 误触发。
                            // activeListener / pendingToken 在外层 remember 持有（见 contentStable 下方），此处直接复用。
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    htmlReady = true
                                    val v = view ?: return
                                    activeListener?.let { v.viewTreeObserver.removeOnGlobalLayoutListener(it) }
                                    pendingToken?.let { v.removeCallbacks(it) }
                                    // UNSPECIFIED 不限高，量出真实内容高度。
                                    // 封面盒固定尺寸、图片加载不改高度（有/无封面高度一致）；
                                    // 这里用布局监听 + 150ms 稳定判定主要兜字体/异步资源落定，
                                    // 等不再变化才展示，避免"先矮后高"两段跳。
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
                                                    contentCssHeight = h.toFloat() / density
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
                            // 长按直接截这个 WebView 存相册——和预览同一个实例，逐像素一致
                            setOnLongClickListener {
                                saveRequested = true
                                true
                            }
                            previewWebView = this
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth(),
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
                // 高度稳定前盖进度圈：等 onPageFinished 用 UNSPECIFIED 量到正确内容高度、并兜住字体/布局落定后，
                // 才把 WebView 亮出来，避免容器"先矮后高"的跳变。封面是固定盒、图片加载不影响高度，与此无关。
                // 换色不改高度、不会走到这里，所以不再"转圈"。
                if (previewFailed) {
                    AppText("预览失败")
                } else if (data == null && !loading) {
                    AppText("生成失败")
                } else if (loading || saving || !contentStable) {
                    CircularProgressIndicator()
                }
            }
        }
    }


    ColorPickerSheet(
        show = showColorPicker,
        initialColor = accentColor ?: ACCENT_PRESETS.first().second,
        onDismissRequest = { showColorPicker = false },
        onColorSelected = { picked ->
            showColorPicker = false
            if (accentColor != picked) {
                accentColor = picked
            }
        },
    )

    // 长按保存：直接把预览 WebView 画到 Bitmap。
    // 不再走离屏渲染——那条路要重建 WebView、重跑一遍 HTML、还得猜什么时候渲染完（所以以前会
    // 永久转圈），而且 viewport 和预览不同，出图和预览必然不一致。
    // 现在 WebView 高度已经等于内容高度、也不滚动，draw() 出来就是完整卡片，逐像素等于预览。
    // try/finally 保证 saving 一定复位（协程被取消时也不会留下卡死的菊花）。
    LaunchedEffect(saveRequested) {
        if (!saveRequested) return@LaunchedEffect
        // 注意：saveRequested 必须等到保存结束（finally）再复位，不能在开头复位。
        // 否则 LaunchedEffect 的 key 在 effect 刚开始就变回 false，Compose 会取消协程；
        // saveToGallery 里的 withContext(Dispatchers.IO) 是首个挂起点，协程在这里被取消，
        // 文件其实已写完（所以"保存成功"），但 withContext 之后的成功 toast 永远执行不到。
        if (data == null) {
            saveRequested = false
            return@LaunchedEffect
        }
        saving = true
        try {
            val wv = previewWebView
            val w = wv?.width ?: 0
            val h = wv?.height ?: 0
            if (wv == null || w <= 0 || h <= 0) {
                context.toastOnUi("保存失败：预览未就绪")
                return@LaunchedEffect
            }
            // draw() 必须在主线程；LaunchedEffect 本身就跑在主线程，无需切换
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            wv.draw(Canvas(bitmap))
            saveToGallery(context, bitmap)
        } finally {
            saving = false
            saveRequested = false
        }
    }
}

@Composable
private fun ColorSwatch(argb: Int) {
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(Color(argb))
    )
}

private suspend fun saveToGallery(context: android.content.Context, bitmap: Bitmap) {
    // PNG 压缩长图很耗时，必须挪到 IO 线程，否则主线程卡顿甚至 ANR。
    // 返回 null 表示成功，非 null 是失败原因后缀。
    val error = withContext(Dispatchers.IO) {
        try {
            val values = android.content.ContentValues().apply {
                put(
                    android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                    "shareCard_${System.currentTimeMillis()}.png",
                )
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Legado")
            }
            val uri = context.contentResolver.insert(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: return@withContext "：无法创建相册文件"
            context.contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            } ?: return@withContext "：无法写入相册文件"
            null
        } catch (e: Exception) {
            "：${e.message}"
        }
    }
    context.toastOnUi(if (error == null) "已保存到相册" else "保存失败$error")
}

