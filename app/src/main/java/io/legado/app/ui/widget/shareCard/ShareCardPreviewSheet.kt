package io.legado.app.ui.widget.shareCard

import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
    // 长按保存：置位后由下面的 LaunchedEffect 走离屏渲染出完整长图再存相册
    var saveRequested by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(show) {
        if (show) {
            accentColor = null
            schemeOverride = null
            htmlReady = false
            previewFailed = false
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

    // WebView 就位或模板切换时加载 HTML（onPageFinished 里翻转 htmlReady 让菊花消失）
    LaunchedEffect(show, selectedTemplateId, previewWebView) {
        if (!show || data == null || selectedTemplateId == 0L) return@LaunchedEffect
        val wv = previewWebView ?: return@LaunchedEffect
        val tpl = templates.firstOrNull { it.id == selectedTemplateId } ?: return@LaunchedEffect
        htmlReady = false
        previewFailed = false
        val html = ShareCardHtmlRenderer.buildPreviewHtml(context, tpl, data)
        if (html.isBlank()) {
            previewFailed = true
            return@LaunchedEffect
        }
        withContext(Dispatchers.Main) {
            wv.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
        }
    }

    // 换色 / 切亮暗 / 页面就绪：只注入一段 JS 改 style 标签内容，页面原地重绘。
    // 这是"瞬间切换"的关键——不 reload、不重建 WebView、不出图。
    LaunchedEffect(htmlReady, accentColor, schemeOverride) {
        if (!htmlReady) return@LaunchedEffect
        val wv = previewWebView ?: return@LaunchedEffect
        wv.evaluateJavascript(
            ShareCardHtmlRenderer.accentApplyJs(accentColor, schemeOverride),
            null,
        )
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
        val previewHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        Box(
            Modifier
                .fillMaxWidth()
                .height(previewHeight),
            contentAlignment = Alignment.Center,
        ) {
            if (data != null) {
                // 实时 WebView 预览：HTML 只加载一次，换色靠 JS 注入原地重绘。
                // 必须无条件挂载（不能被 htmlReady 门控），否则 WebView 建不起来、HTML 无从加载。
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
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                setSupportZoom(false)
                                builtInZoomControls = false
                                blockNetworkLoads = false
                                blockNetworkImage = false
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            }
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    htmlReady = true
                                }
                            }
                            // 长按保存到相册（走离屏渲染出完整长图，比截当前视口更完整）
                            setOnLongClickListener {
                                saveRequested = true
                                true
                            }
                            previewWebView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = {
                        it.stopLoading()
                        it.destroy()
                        previewWebView = null
                    },
                )
            }
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

    // 长按保存：通过离屏渲染生成完整长图（跟当前所选 accent/scheme 一致）
    // try/finally 保证 saving 一定复位；否则 render() 抛异常或被父作用域取消时，
    // 菊花会永久卡住——即便切换模板、退出重进也停不下来（saving 状态在 sheet 内被 remember）。
    LaunchedEffect(saveRequested) {
        if (!saveRequested || data == null) return@LaunchedEffect
        saveRequested = false
        saving = true
        try {
            val tpl = templates.firstOrNull { it.id == selectedTemplateId }
                ?: return@LaunchedEffect
            val bitmap = ShareCardHtmlRenderer.render(context, tpl, data, accentColor, schemeOverride)
            if (bitmap != null) {
                saveToGallery(context, bitmap)
            } else {
                context.toastOnUi("保存失败")
            }
        } finally {
            saving = false
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

