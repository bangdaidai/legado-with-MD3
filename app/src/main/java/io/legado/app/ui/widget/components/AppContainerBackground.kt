package io.legado.app.ui.widget.components

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import io.legado.app.domain.model.settings.ContainerNineSlice
import io.legado.app.domain.model.settings.ThemeSettings
import io.legado.app.domain.model.settings.parseContainerNineSlice
import io.legado.app.feature.reader.platform.ReaderTextBackgroundLoader
import io.legado.app.help.highlight.NinePatchDrawHelper
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.LocalAppUiConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

enum class AppContainerBackgroundType {
    Large,
    Item,
}

/** Paints the configured container image behind this layout's content. */
@Composable
fun Modifier.appContainerBackground(
    type: AppContainerBackgroundType = AppContainerBackgroundType.Large,
    backgroundImage: String? = null,
    useThemeBackground: Boolean = true,
    backgroundAlpha: Float? = null,
    contentScale: ContentScale = ContentScale.Crop,
): Modifier {
    val theme = LocalAppUiConfiguration.current.theme
    val isDark = LegadoTheme.isDark
    val useConfigured = useThemeBackground && theme.enableContainerBackgroundImage
    val configuredImage = if (useConfigured) theme.containerBackgroundImagePath(type, isDark) else null
    val path = backgroundImage ?: configuredImage
    if (path.isNullOrBlank()) return this
    val density = LocalDensity.current

    val alpha = backgroundAlpha ?: when (type) {
        AppContainerBackgroundType.Large -> theme.appColumnBackgroundOpacity / 100f
        AppContainerBackgroundType.Item -> theme.glassCardBackgroundOpacity / 100f
    }
    // 切分线与高亮背景图同一口径：编辑器保存过的手动线优先，
    // 未保存时 .9.png 按引导线自动探测；两者都没有则保持整图平铺。
    // 自动探测要解码图片，与位图加载一起在 IO 线程完成，不进组合线程。
    val savedNineSlice = if (useConfigured && backgroundImage == null) {
        theme.containerBackgroundNineSlice(type, isDark)
    } else {
        null
    }
    val borderPx = if (isRawNinePatchPath(path)) 1f else 0f
    // 局部解构不能用 `by` 委托（Kotlin 限制），先取 State 再解构它的值
    val nineResolved = produceState<Pair<Bitmap?, ContainerNineSlice?>>(
        initialValue = null to null,
        path,
        savedNineSlice,
    ) {
        value = withContext(Dispatchers.IO) {
            val slice = parseContainerNineSlice(savedNineSlice)
                ?: ReaderTextBackgroundLoader.nineSliceFractions(path)?.let {
                    ContainerNineSlice(it.left, it.right, it.top, it.bottom)
                }
            if (slice == null) {
                null to null
            } else {
                ReaderTextBackgroundLoader.load(path) to slice
            }
        }
    }
    val (ninePatchBitmap, nineSlice) = nineResolved.value
    // 图案缩放：由设置页「图案大小」滑块显式指定，日夜图共用一个值
    val nineScale = if (useConfigured && backgroundImage == null) {
        theme.containerBackgroundNineScale(type)
    } else {
        1f
    }
    val resolvedAlpha = alpha.coerceIn(0f, 1f)

    val bitmap = ninePatchBitmap
    if (bitmap != null && nineSlice != null) {
        val lines = nineSlice
        // 四角/四边图案目标尺寸 = 原图像素 × scale，以 1px=1dp 为基准换算成屏上像素；
        // 不再按容器高度自动等比，容器比图案大时多出来的部分全部由中段拉伸吸收。
        val pxPerDp = with(density) { 1.dp.toPx() }
        val contentW = (bitmap.width - borderPx * 2f).coerceAtLeast(0f)
        val contentH = (bitmap.height - borderPx * 2f).coerceAtLeast(0f)
        val cornerL = lines.left * contentW * pxPerDp * nineScale
        val cornerR = lines.right * contentW * pxPerDp * nineScale
        val cornerT = lines.top * contentH * pxPerDp * nineScale
        val cornerB = lines.bottom * contentH * pxPerDp * nineScale
        val paint = remember {
            Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            }
        }
        return this.drawWithContent {
            drawIntoCanvas { canvas ->
                val androidCanvas = canvas.nativeCanvas
                val saveCount = androidCanvas.saveLayerAlpha(
                    0f, 0f, size.width, size.height,
                    (resolvedAlpha * 255).toInt()
                )
                // 与高亮九宫格同一套绘制：四角/四边保持设定尺寸，只有中段被拉伸
                NinePatchDrawHelper.draw(
                    canvas = androidCanvas,
                    bitmap = bitmap,
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    paint = paint,
                    leftX = lines.leftX,
                    rightX = lines.rightX,
                    topY = lines.topY,
                    bottomY = lines.bottomY,
                    cornerL = cornerL,
                    cornerR = cornerR,
                    cornerT = cornerT,
                    cornerB = cornerB,
                    borderPx = borderPx,
                )
                androidCanvas.restoreToCount(saveCount)
            }
            drawContent()
        }
    }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val requestWidth = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val requestHeight = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    val request = remember(context, path, requestWidth, requestHeight) {
        ImageRequest.Builder(context)
            .data(path)
            .size(requestWidth.coerceAtLeast(1), requestHeight.coerceAtLeast(1))
            .build()
    }
    val painter = rememberAsyncImagePainter(
        model = request,
        imageLoader = koinInject(),
    )
    return this
        .paint(
            painter = painter,
            sizeToIntrinsics = false,
            contentScale = contentScale,
            alpha = resolvedAlpha,
        )
}

/** 取当前主题下该容器槽位（大容器/项目 × 日/夜）的背景图路径 */
private fun ThemeSettings.containerBackgroundImagePath(
    type: AppContainerBackgroundType,
    isDark: Boolean,
): String? = when (type) {
    AppContainerBackgroundType.Large -> if (isDark) {
        largeContainerBackgroundImageDark
    } else {
        largeContainerBackgroundImageLight
    }
    AppContainerBackgroundType.Item -> if (isDark) {
        itemBackgroundImageDark
    } else {
        itemBackgroundImageLight
    }
}

/** 取该槽位保存过的九宫格切分线 CSV（未保存过为 null） */
private fun ThemeSettings.containerBackgroundNineSlice(
    type: AppContainerBackgroundType,
    isDark: Boolean,
): String? = when (type) {
    AppContainerBackgroundType.Large -> if (isDark) {
        largeContainerNineSliceDark
    } else {
        largeContainerNineSliceLight
    }
    AppContainerBackgroundType.Item -> if (isDark) {
        itemNineSliceDark
    } else {
        itemNineSliceLight
    }
}

/** 该容器类型的九宫格图案缩放（日/夜共用一个值，对齐高亮 bgImageScale 先例） */
private fun ThemeSettings.containerBackgroundNineScale(
    type: AppContainerBackgroundType,
): Float = when (type) {
    AppContainerBackgroundType.Large -> largeContainerNineSliceScale
    AppContainerBackgroundType.Item -> itemNineSliceScale
}

/** 与 ReaderTextBackgroundLoader 的 .9.png 判定保持同一后缀规则 */
private fun isRawNinePatchPath(path: String): Boolean =
    path.substringBefore('?').substringBefore('#').endsWith(".9.png", ignoreCase = true)
