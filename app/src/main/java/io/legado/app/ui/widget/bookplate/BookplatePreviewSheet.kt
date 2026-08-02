package io.legado.app.ui.widget.bookplate

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.BookplateData
import io.legado.app.data.entities.BookplateTemplate
import io.legado.app.data.repository.BookplateRepository
import io.legado.app.help.book.BookplateGenerator
import io.legado.app.help.book.BookplateHtmlRenderer
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenu
import io.legado.app.ui.widget.components.menuItem.RoundDropdownMenuItem
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

@Composable
fun BookplatePreviewSheet(
    show: Boolean,
    data: BookplateData?,
    initialBitmap: Bitmap?,
    loading: Boolean,
    onDismissRequest: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bookplateRepository = koinInject<BookplateRepository>()

    var templates by remember { mutableStateOf<List<BookplateTemplate>>(emptyList()) }
    var selectedTemplateId by remember { mutableLongStateOf(0L) }
    var currentBitmap by remember { mutableStateOf(initialBitmap) }
    var rendering by remember { mutableStateOf(false) }
    var showTemplateMenu by remember { mutableStateOf(false) }

    LaunchedEffect(initialBitmap) { currentBitmap = initialBitmap }

    LaunchedEffect(show) {
        if (show) {
            templates = withContext(Dispatchers.IO) {
                BookplateGenerator.getOrCreateBuiltinTemplates()
                bookplateRepository.getAll()
            }
        }
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = "藏书票",
        startAction = {
            Box {
                MediumTonalButton(
                    onClick = { showTemplateMenu = true },
                    enabled = templates.isNotEmpty() && !rendering && !loading,
                    icon = Icons.Default.SwapHoriz,
                    contentDescription = "切换模板",
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
                                if (data == null || tpl.id == selectedTemplateId) return@RoundDropdownMenuItem
                                selectedTemplateId = tpl.id
                                scope.launch {
                                    rendering = true
                                    val bmp = BookplateHtmlRenderer.render(context, tpl, data)
                                    currentBitmap = bmp
                                    rendering = false
                                }
                            },
                        )
                    }
                }
            }
        },
        endAction = {
            MediumTonalButton(
                onClick = { currentBitmap?.let { saveToGallery(context, it) } },
                enabled = currentBitmap != null && !rendering && !loading,
                icon = Icons.Default.Save,
                contentDescription = "保存到相册",
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
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.7f).dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Image(
                        bitmap = currentBitmap!!.asImageBitmap(),
                        contentDescription = "藏书票",
                        modifier = Modifier.fillMaxWidth(),
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
}

private fun saveToGallery(context: android.content.Context, bitmap: Bitmap) {
    try {
        val values = android.content.ContentValues().apply {
            put(
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                "bookplate_${System.currentTimeMillis()}.png",
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

