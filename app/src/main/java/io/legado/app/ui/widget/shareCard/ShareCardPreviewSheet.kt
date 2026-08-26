package io.legado.app.ui.widget.shareCard

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.ShareCardData
import io.legado.app.data.entities.ShareCardTemplate
import io.legado.app.data.repository.ShareCardRepository
import io.legado.app.help.book.ShareCardGenerator
import io.legado.app.ui.book.shareCard.ShareCardScene
import io.legado.app.help.book.ShareCardHtmlRenderer
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.dialog.ColorPickerSheet
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/** 换色可选的预设主题色，最后一项走取色器自定义 */
private val ACCENT_PRESETS = listOf(
    "薄荷绿" to 0xFF6BCBB1.toInt(),
    "樱花粉" to 0xFFF4A9B6.toInt(),
    "海蓝色" to 0xFF5BA3D9.toInt(),
    "奶油黄" to 0xFFE6C97A.toInt(),
    "雾霭紫" to 0xFF9B8BC0.toInt(),
    "石墨灰" to 0xFF6B737D.toInt(),
)

/** 渲染超过这个时长才显示顶部进度条，避免快路径一闪 */
private const val PROGRESS_DELAY_MS = 300L

/** 首图迟迟不就绪时的开门兜底时长，避免「点了生成什么都不出现」 */
private const val OPEN_FAILSAFE_MS = 3000L

/**
 * 分享卡片预览面板。
 *
 * 预览显示的是**离屏渲好的一张 Bitmap**，不是活的 WebView——Bitmap 尺寸自带，
 * 所以没有测量、没有二段跳、Sheet 打开后内容大小再也不变。
 * 切模板/换色时旧图留在屏幕上、顶部走一条细进度条，新图好了一帧换掉，不闪空菊花。
 */
@Composable
fun ShareCardPreviewSheet(
    show: Boolean,
    data: ShareCardData?,
    loading: Boolean,
    onDismissRequest: () -> Unit,
    scene: ShareCardScene? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareCardRepository = koinInject<ShareCardRepository>()

    var templates by remember { mutableStateOf<List<ShareCardTemplate>>(emptyList()) }
    var visibleTemplates by remember { mutableStateOf<List<ShareCardTemplate>>(emptyList()) }
    // 按场景记忆上次选中的模板：切换场景时归零并重新解析该场景的默认/绑定模板
    var selectedTemplateId by remember(scene) { mutableLongStateOf(0L) }
    var showTemplateMenu by remember { mutableStateOf(false) }
    // 临时主题色：null = 用模板自身配色，不写库、关闭后不保留
    var accentColor by remember { mutableStateOf<Int?>(null) }
    var showPaletteMenu by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    // 方案覆盖：null=自动（按所选色明度判定），false=强制亮，true=强制暗。
    var schemeOverride by remember { mutableStateOf<Boolean?>(null) }

    // 当前显示的卡片图。重渲期间保持旧图不动，新图就绪才替换。
    var currentBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // 当前图对应的模板 id：开门时据此判断是否需要重渲，避免显示错模板的图。
    var currentTemplateId by remember { mutableLongStateOf(0L) }
    // 当前图对应的数据：与 currentTemplateId 一起标记「当前图是否就是这份 data + 所选模板」，
    // 命中则不再重渲，避免开门/切模板时的重复渲染与跳变。
    var currentData by remember { mutableStateOf<ShareCardData?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var renderFailed by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // 当前渲染 Job：连续切色/切日夜时取消上一个，避免旧 render 的 draw 后到、覆盖新图
    // （表现为「切不回/卡一个颜色」）。
    var renderJob by remember { mutableStateOf<Job?>(null) }
    // 进度条延迟显示：缓存命中 / 同内容增量换色只要几十毫秒，rendering 从 true 到 false
    // 只跨一两帧，进度条一显一隐就是「闪一下」。等够 PROGRESS_DELAY_MS 才显示——
    // 快路径这期间已经结束、LaunchedEffect 被取消，进度条根本不出现；
    // 慢路径（换模板全量 loadData）照常提示。
    var showProgress by remember { mutableStateOf(false) }
    LaunchedEffect(rendering) {
        if (!rendering) {
            showProgress = false
            return@LaunchedEffect
        }
        delay(PROGRESS_DELAY_MS)
        showProgress = true
    }

    fun rerender(templateId: Long, accent: Int?, forceDark: Boolean?) {
        val d = data ?: return
        val tpl = templates.firstOrNull { it.id == templateId } ?: return
        renderJob?.cancel()
        renderJob = scope.launch {
            rendering = true
            val bmp = ShareCardHtmlRenderer.render(context, tpl, d, accent, forceDark)
            if (bmp != null) {
                currentBitmap = bmp
                currentTemplateId = templateId
                currentData = d
                renderFailed = false
            } else if (currentBitmap == null) {
                renderFailed = true
            }
            rendering = false
        }
    }

    fun saveCurrentBitmap() {
        val bmp = currentBitmap ?: return
        scope.launch {
            saving = true
            try {
                saveToGallery(context, bmp)
            } finally {
                saving = false
            }
        }
    }

    // 进页面就把模板列表加载好（IO），开门时零等待、不再因模板查询而空白。
    LaunchedEffect(Unit) {
        templates = withContext(Dispatchers.IO) {
            ShareCardGenerator.getOrCreateBuiltinTemplates()
            shareCardRepository.getAll()
        }
    }

    // 开门：预热 WebView + 复位瞬时态；按场景过滤可用模板，并解析默认。
    LaunchedEffect(show, templates) {
        if (!show || templates.isEmpty()) return@LaunchedEffect
        ShareCardHtmlRenderer.warm(context)
        accentColor = null
        schemeOverride = null
        renderFailed = false
        saving = false
        // 场景绑定了分组 → 仅显示这些分组的并集；未绑定/无模板 → 显示全部
        val allowedGroups = if (scene != null) {
            withContext(Dispatchers.IO) { shareCardRepository.getGroupsForScene(scene.key) }
        } else emptyList()
        visibleTemplates = if (scene == null || allowedGroups.isEmpty()) {
            templates
        } else {
            templates.filter { allowedGroups.contains(it.groupName) }
        }
        if (selectedTemplateId == 0L) {
            val saved = withContext(Dispatchers.IO) {
                shareCardRepository.getSelectedTemplateId(scene?.key)
            }
            selectedTemplateId = visibleTemplates.firstOrNull { it.id == saved }?.id
                ?: visibleTemplates.firstOrNull {
                    it.isBuiltin && it.groupName == ShareCardTemplate.DEFAULT_GROUP_BOOK
                }?.id
                ?: visibleTemplates.firstOrNull()?.id
                ?: 0L
        }
    }

    // 渲染协调：开门/切模板时，仅当「当前图不是这份 data 的所选模板」才重渲。
    // 命中（currentData==data 且 currentTemplateId==selectedTemplateId）则不动，避免重复渲染与跳变；
    // 旧图在重渲期间保留、顶部走细进度条，不闪空白。
    LaunchedEffect(show, selectedTemplateId, data, templates) {
        if (!show || data == null || selectedTemplateId == 0L || templates.isEmpty()) {
            return@LaunchedEffect
        }
        if (currentData == data && currentTemplateId == selectedTemplateId) {
            return@LaunchedEffect
        }
        if (!rendering) rerender(selectedTemplateId, accentColor, schemeOverride)
    }

    // 首图就绪后才真正开门。开门前内容高度是占位框（屏高 0.85），开门后是图片自身高度，
    // 两者不一致会被 AppModalBottomSheet 的 animateContentSize 弹簧做成「顶高再回弹」的抖动。
    // 等图好了再开门，弹窗一上来就是最终高度，全程零尺寸变化。
    // 渲染失败 / 数据生成失败也要开门，否则用户点了没反应。
    val readyToOpen = (
        currentBitmap != null &&
            currentData == data &&
            currentTemplateId == selectedTemplateId
        ) || renderFailed || (data == null && !loading)
    var sheetOpened by remember { mutableStateOf(false) }
    LaunchedEffect(show, readyToOpen) {
        if (!show) {
            sheetOpened = false
            return@LaunchedEffect
        }
        if (readyToOpen) {
            sheetOpened = true
            return@LaunchedEffect
        }
        // 兜底：模板列表为空等极端情况下渲染既不成功也不置 renderFailed，
        // 不兜住就会「点了生成什么都不出现」，比抖动更糟。
        delay(OPEN_FAILSAFE_MS)
        sheetOpened = true
    }

    AppModalBottomSheet(
        show = show && sheetOpened,
        onDismissRequest = onDismissRequest,
        title = "分享卡片",
        startAction = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    MediumTonalButton(
                        onClick = { showTemplateMenu = true },
                        // 不把 rendering 计入 enabled：这两个按钮只负责「打开模板/色盘菜单」，不直接出图；
                        // 渲染中开菜单无害，真正选中项时 rerender 已用 renderJob.cancel 串行化。
                        // 计入 rendering 会让快路径换色时按钮在正常/禁用色间闪一下。
                        enabled = visibleTemplates.isNotEmpty() && !loading,
                        icon = Icons.Default.SwapHoriz,
                        contentDescription = "切换模板",
                    )
                    RoundDropdownMenu(
                        expanded = showTemplateMenu,
                        onDismissRequest = { showTemplateMenu = false },
                    ) { dismiss ->
                        visibleTemplates.forEach { tpl ->
                            RoundDropdownMenuItem(
                                text = tpl.name.ifBlank { "未命名" },
                                isSelected = tpl.id == selectedTemplateId,
                                onClick = {
                                    dismiss()
                                    if (tpl.id == selectedTemplateId) return@RoundDropdownMenuItem
                                    selectedTemplateId = tpl.id
                                    // 记住该场景下上次选中的模板
                                    shareCardRepository.setSelectedTemplateId(scene?.key, tpl.id)
                                    // 切模板复位临时配色，新模板用自身默认色起手；
                                    // 实际渲染交由下方协调 effect（selectedTemplateId 变化触发）
                                    accentColor = null
                                    schemeOverride = null
                                },
                            )
                        }
                    }
                }
                Box {
                    MediumTonalButton(
                        onClick = { showPaletteMenu = true },
                        enabled = visibleTemplates.isNotEmpty() && !loading,
                        selected = accentColor != null,
                        icon = Icons.Default.Palette,
                        contentDescription = "切换颜色",
                    )
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
                                rerender(selectedTemplateId, null, null)
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
                                    // 选色回到「按该色明度自动判明暗」，明暗只由日夜按钮独立控制；
                                    // 不再沿用上一次方案，避免「从暗色切到新色直接是暗」的困惑。
                                    schemeOverride = null
                                    rerender(selectedTemplateId, argb, null)
                                },
                            )
                        }
                        RoundDropdownMenuItem(
                            text = "自定义…",
                            isSelected = accentColor != null &&
                                ACCENT_PRESETS.none { it.second == accentColor },
                            onClick = {
                                dismiss()
                                showColorPicker = true
                            },
                        )
                    }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediumTonalButton(
                    onClick = {
                        val next = effectiveDark != true
                        schemeOverride = next
                        rerender(selectedTemplateId, accentColor, next)
                    },
                    // 只去掉初始灰色：点击逻辑与之前完全一致，accentColor 为空时切换本身无效果
                    enabled = !loading,
                    selected = schemeOverride != null,
                    icon = if (effectiveDark == true) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                    contentDescription = "切换亮/暗（当前${if (effectiveDark == true) "暗色" else "亮色"}）",
                )
                MediumTonalButton(
                    onClick = { saveCurrentBitmap() },
                    // 不把 rendering 计入 enabled：保存的是当前已渲染好的旧图，重渲期间仍可保存；
                    // 计入 rendering 会让快路径换色时按钮在正常/禁用色间闪一下。
                    enabled = currentBitmap != null && !saving,
                    icon = Icons.Default.Save,
                    contentDescription = "保存卡片",
                )
            }
        },
    ) {
        val maxPreviewHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        val bmp = currentBitmap
        Box(Modifier.fillMaxWidth()) {
            when {
                bmp != null -> {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxPreviewHeight)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "分享卡片",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth,
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
                        when {
                            renderFailed -> AppText("预览失败")
                            data == null && !loading -> AppText("生成失败")
                            // 没有旧图可留时（首渲/数据生成中）显示转圈，比空白诚实
                            else -> CircularProgressIndicator()
                        }
                    }
                }
            }
            // 重渲期间只在顶部走一条细进度条，旧图继续显示，不清空、不闪菊花。
            // showProgress 延迟判定：快路径（缓存/增量换色）几十毫秒内结束，不显示，避免一闪。
            if (showProgress && bmp != null) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                )
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
                // 与预设色一致：自定义取色也回到自动明暗，明暗只由日夜按钮控制
                schemeOverride = null
                rerender(selectedTemplateId, picked, null)
            }
        },
    )
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
