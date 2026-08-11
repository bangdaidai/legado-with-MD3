package io.legado.app.ui.widget.shareCard

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import kotlinx.coroutines.launch
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
    initialBitmap: Bitmap?,
    loading: Boolean,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shareCardRepository = koinInject<ShareCardRepository>()

    var templates by remember { mutableStateOf<List<ShareCardTemplate>>(emptyList()) }
    var selectedTemplateId by remember { mutableLongStateOf(0L) }
    var currentBitmap by remember { mutableStateOf(initialBitmap) }
    var rendering by remember { mutableStateOf(false) }
    var showTemplateMenu by remember { mutableStateOf(false) }
    // 临时主题色：null = 用模板自身配色，不写库、关闭后不保留
    var accentColor by remember { mutableStateOf<Int?>(null) }
    var showPaletteMenu by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    // 方案覆盖：null=自动（按所选色明度判定），false=强制亮，true=强制暗。便于预览另一种效果。
    var schemeOverride by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(initialBitmap) { currentBitmap = initialBitmap }

    LaunchedEffect(show) {
        if (show) {
            accentColor = null
            schemeOverride = null
            val loaded = withContext(Dispatchers.IO) {
                ShareCardGenerator.getOrCreateBuiltinTemplates()
                shareCardRepository.getAll()
            }
            templates = loaded
            // 每次打开都按已保存的模板 id 同步，保证 selectedTemplateId 与 initialBitmap
            // 所用模板一致——否则用户长按换色时可能用错模板重渲染。
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

    fun rerender(templateId: Long, accent: Int?, forceDark: Boolean? = schemeOverride) {
        if (data == null) return
        val tpl = templates.firstOrNull { it.id == templateId } ?: return
        scope.launch {
            rendering = true
            currentBitmap = ShareCardHtmlRenderer.render(context, tpl, data, accent, forceDark)
            rendering = false
        }
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = "分享卡片",
        startAction = {
            Box {
                MediumTonalButton(
                    onClick = { showTemplateMenu = true },
                    onLongClick = { if (templates.isNotEmpty() && !rendering && !loading) showPaletteMenu = true },
                    enabled = templates.isNotEmpty() && !rendering && !loading,
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
                                rerender(tpl.id, accentColor)
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
                            rerender(selectedTemplateId, null)
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
                                rerender(selectedTemplateId, argb)
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
            // 亮/暗切换（太阳/月亮图标）：仅在已选主题色时生效（accentColor=null 不注入变量，无意义）。
            // 点击在当前亮/暗之间翻转。图标显示当前方案，长按图片则自动保存到相册。
            val effectiveDark = if (accentColor == null) {
                null
            } else {
                schemeOverride ?: ShareCardHtmlRenderer.isDarkByDefault(accentColor)
            }
            MediumTonalButton(
                onClick = {
                    val next = if (effectiveDark == true) false else true
                    schemeOverride = next
                    rerender(selectedTemplateId, accentColor, next)
                },
                enabled = accentColor != null && !rendering && !loading,
                selected = schemeOverride != null,
                icon = if (effectiveDark == true) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                contentDescription = "切换亮/暗（当前${if (effectiveDark == true) "暗色" else "亮色"}）",
            )
        },
    ) {
        // Preview image only
        when {
            rendering || loading -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            currentBitmap != null -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Image(
                        bitmap = currentBitmap!!.asImageBitmap(),
                        contentDescription = "分享卡片",
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                enabled = !rendering && !loading,
                                onClick = {},
                                onLongClick = { currentBitmap?.let { saveToGallery(context, it) } },
                            ),
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }
            else -> {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    contentAlignment = Alignment.Center,
                ) {
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
                rerender(selectedTemplateId, picked)
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

private fun saveToGallery(context: android.content.Context, bitmap: Bitmap) {
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
        )
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            context.toastOnUi("已保存到相册")
        } else {
            context.toastOnUi("保存失败")
        }
    } catch (e: Exception) {
        context.toastOnUi("保存失败: ${e.message}")
    }
}

