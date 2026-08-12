package io.legado.app.ui.widget.shareCard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
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
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.delay
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
    // 由 measure 量出的 WebView 内容真实高度(原始 px)。WebView 用 MATCH_PARENT 填满内层固定高度的 Box，
    // 固定高度由量出的真实值决定（矮图矮、高图高），量不出只用小兜底、不撑屏。
    // 不在 onPageFinished 里立即量：那时图片/字体未加载，量到的是偏小的初始值且不再更新。
    var contentCssHeight by remember { mutableStateOf<Float?>(null) }
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
        val html = ShareCardHtmlRenderer.buildPreviewHtml(tpl, data)
        if (html.isBlank()) {
            previewFailed = true
            return@LaunchedEffect
        }
        withContext(Dispatchers.Main) {
            wv.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
        }
    }

    // 量真实内容高度：用 View.measure() 量出 WebView 完整内容高度，替代 evaluateJavascript，
    // 免去 JS 执行时机/字符串解析的不确定性。等 htmlReady 翻转 + delay 等图片/字体撑稳后再量。
    // 矮图量到矮、高图量到高；量不出(width 未就绪/为0)不兜底整屏，仅用小框，避免矮图被撑出大段背景。
    // measure 出来的 measuredHeight 是原始 px，和下方 contentCssHeight/density 转 dp 的口径一致。
    LaunchedEffect(htmlReady, accentColor, schemeOverride, previewWebView) {
        val wv = previewWebView ?: return@LaunchedEffect
        if (!htmlReady) return@LaunchedEffect
        if (wv.width <= 0) return@LaunchedEffect
        delay(120)
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(wv.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val px = wv.measuredHeight
        contentCssHeight = if (px > 0) px.toFloat() else null
    }

    // 换色 / 切亮暗 / 页面就绪：只注入一段 JS 改 style 标签内容，页面原地重绘。
    // 这是"瞬间切换"的关键——不 reload、不重建 WebView、不出图。
    // 换色后内容高度理论不变（只改色），但为稳妥仍会在上面 effect 里重新量一次。
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
        // 高度用 measure 量出的 contentCssHeight 决定：矮图矮、高图高；量不出只用小兜底、不撑屏。
        // WebView 用 MATCH_PARENT 填满内层固定高度的 Box（不靠 WRAP_CONTENT），避免资源异步加载
        // 后反复 requestLayout 的布局抖动（卡顿）；固定高度由 Compose 侧定死，Web 框不抖。
        // 关键：WebView 必须在弹窗一打开就无条件挂载（不能被 data 门控），让 WebView 的创建开销
        // 与入场动画重叠；否则会先弹一个小加载圈、等数据 IO 完才挂载 WebView 并撑高到全高，
        // 二次撑高落在入场动画里就成了"先出来一点、卡顿、再整个弹出"。
        val density = LocalDensity.current.density
        val maxPreviewHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        // 内容高(dp)：量出用真实值；量不出用小兜底(240dp)，绝不兜底整屏（否则矮图被撑出大段背景）。
        val contentDp = if (contentCssHeight != null && contentCssHeight!! > 0f) {
            (contentCssHeight!! / density).dp
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
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    htmlReady = true
                                    // 真实高度由独立 LaunchedEffect 在 delay 等图片撑稳后量，
                                    // 不在这里立即量（那时资源未加载，量到偏小值且不再更新）。
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
                        it.stopLoading()
                        it.destroy()
                        previewWebView = null
                    },
                )
                // 首帧加载中 / 保存中：盖一层进度指示。换色不会走到这里，所以不再"转圈"。
                if (loading || saving || (data != null && !htmlReady && !previewFailed)) {
                    CircularProgressIndicator()
                }
                if (previewFailed) {
                    AppText("预览失败")
                }
                if (data == null && !loading) {
                    AppText("生成失败")
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

