package io.legado.app.ui.widget.bookplate

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookplateData
import io.legado.app.data.entities.BookplateTemplate
import io.legado.app.help.book.BookplateGenerator
import io.legado.app.help.book.BookplateHtmlRenderer
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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

    var templates by remember { mutableStateOf<List<BookplateTemplate>>(emptyList()) }
    var selectedTemplateId by remember { mutableLongStateOf(0L) }
    var currentBitmap by remember { mutableStateOf(initialBitmap) }
    var rendering by remember { mutableStateOf(false) }

    LaunchedEffect(initialBitmap) { currentBitmap = initialBitmap }

    LaunchedEffect(show) {
        if (show) {
            templates = withContext(Dispatchers.IO) {
                BookplateGenerator.getOrCreateBuiltinTemplates()
                appDb.bookplateTemplateDao.getAll()
            }
        }
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = "藏书票",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 1. Preview image
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
                            .heightIn(max = 400.dp)
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

            // 2. Template switcher
            if (templates.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                AppText(
                    "切换模板",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp),
                    style = LegadoTheme.typography.bodySmall,
                )
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(templates, key = { it.id }) { tpl ->
                        val selected = tpl.id == selectedTemplateId
                        FilterChip(
                            selected = selected,
                            onClick = {
                                if (data == null || tpl.id == selectedTemplateId) return@FilterChip
                                selectedTemplateId = tpl.id
                                scope.launch {
                                    rendering = true
                                    val bmp = BookplateHtmlRenderer.render(context, tpl, data)
                                    currentBitmap = bmp
                                    rendering = false
                                }
                            },
                            label = {
                                AppText(
                                    tpl.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }

            // 3. Action buttons
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { currentBitmap?.let { saveToGallery(context, it) } },
                    modifier = Modifier.weight(1f),
                    enabled = currentBitmap != null && !rendering && !loading,
                ) { AppText("保存到相册") }
                Button(
                    onClick = { currentBitmap?.let { shareBitmap(context, it) } },
                    modifier = Modifier.weight(1f),
                    enabled = currentBitmap != null && !rendering && !loading,
                ) { AppText("分享") }
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

private fun shareBitmap(context: android.content.Context, bitmap: Bitmap) {
    try {
        val cacheFile = File(context.cacheDir, "bookplate_share.png")
        FileOutputStream(cacheFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileProvider",
            cacheFile,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享藏书票"))
    } catch (e: Exception) {
        context.toastOnUi("分享失败: ${e.message}")
    }
}
