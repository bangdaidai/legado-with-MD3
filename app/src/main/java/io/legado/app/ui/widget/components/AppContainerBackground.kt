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
import io.legado.app.domain.model.settings.BookshelfSettings
import io.legado.app.domain.model.settings.ThemeSettings
import io.legado.app.domain.model.settings.parseContainerNineSlice
import io.legado.app.feature.reader.platform.ReaderTextBackgroundLoader
import io.legado.app.help.highlight.NinePatchDrawHelper
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.LocalAppUiConfiguration
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.math.roundToInt

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
    /** 显式九宫格切分线 CSV（书架卡片这类自带槽位用；null 时走主题容器槽位） */
    nineSliceCsv: String? = null,
    /** 显式图案缩放（100% = 原图 1px 画 1 屏幕像素），不传时用槽位保存值或 1f */
    nineScale: Float = 1f,
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
    val savedNineSlice = nineSliceCsv ?: if (useConfigured && backgroundImage == null) {
        theme.containerBackgroundNineSlice(type, isDark)
    } else {
        null
    }
    val borderPx = if (isRawNinePatchPath(path)) 1f else 0f
    // 九宫格解析分三级，核心是消灭"先整图 Crop、解码完再跳回九宫格"的尺寸突变
    // （真机反馈：书架滚动时刚进入视口的行图案先大一点再回正常）：
    // 1) 位图已在内存缓存 → 组合线程同步解析（切分线解析与 .9.png 引导线探测结果各有缓存，
    //    回收行重组不重复解码/扫像素），页面返回与滚动的首帧即九宫格；
    // 2) 普通图且没有保存切分线 → 永远不会有九宫格，直接走整图绘制，不需要异步；
    // 3) 其余是冷缓存：异步解码期间什么都不画，让卡片/容器底色透出，就绪后一次成型。
    //    不再用整图 Crop 当加载预览——那正是"先大一点"本身；也不再每实例各自
    //    produceState 排队解码，导致整页容器一张一张"卡卡的"补出来。
    val syncNinePatch = resolveNinePatchSync(path, savedNineSlice)
    val plainWholeImage = savedNineSlice.isNullOrBlank() && !isRawNinePatchPath(path)
    val asyncNinePatch = if (syncNinePatch == null && !plainWholeImage) {
        produceState<Pair<Bitmap?, ContainerNineSlice?>>(
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
        }.value
    } else {
        null
    }
    val bitmap = syncNinePatch?.first ?: asyncNinePatch?.first
    val lines = syncNinePatch?.second ?: asyncNinePatch?.second
    // .9.png 无有效引导线等：异步已跑完（first != null 说明解码完成）但判定该图永远走整图，
    // 交给下面的 Crop 分支。
    // 注意：asyncNinePatch 初始值是 null to null（Pair 本身非 null），必须检查 first
    // 是否已加载——首次冷缓存时 first == null，此时不能判定为"整图"，否则 Coil 整图
    // Crop 预览会闪一帧再跳回九宫格尺寸（真机反馈：书架滚动新进视口行图案先大再跳正常）。
    val asyncReady = asyncNinePatch?.first != null
    val wholeImageOnly = plainWholeImage ||
        (syncNinePatch == null && asyncReady && lines == null)
    // 图案缩放：由设置页「图案大小」滑块显式指定，日夜图共用一个值；
    // 独立槽位（如书架卡片背景图）直接经参数传入，优先于主题槽位
    val resolvedNineScale = if (nineScale != 1f) {
        nineScale
    } else if (useConfigured && backgroundImage == null) {
        theme.containerBackgroundNineScale(type)
    } else {
        1f
    }
    val resolvedAlpha = alpha.coerceIn(0f, 1f)

    if (bitmap != null && lines != null) {
        // 四角/四边图案目标尺寸 = 原图像素 × scale，以 1px=1 屏幕像素 为基准：
        // 100% 即"这张图在手机上看多大，图案就多大"（真机反馈 1px=1dp 基准
        // 在高密度屏上把图案放大约 3 倍，右下角角块巨大）。
        // 不再按容器高度自动等比，容器比图案大时多出来的部分全部由中段拉伸吸收。
        val contentW = (bitmap.width - borderPx * 2f).coerceAtLeast(0f)
        val contentH = (bitmap.height - borderPx * 2f).coerceAtLeast(0f)
        val cornerL = lines.left * contentW * resolvedNineScale
        val cornerR = lines.right * contentW * resolvedNineScale
        val cornerT = lines.top * contentH * resolvedNineScale
        val cornerB = lines.bottom * contentH * resolvedNineScale
        val paint = remember {
            Paint().apply {
                isAntiAlias = true
                isFilterBitmap = true
            }
        }
        return this.drawWithContent {
            drawIntoCanvas { canvas ->
                // 透明度烘进 Paint：九宫格各格严格平铺、互不重叠，单画笔 alpha
                // 与整层合成结果一致；不再每卡每帧 saveLayer 分配离屏缓冲（真机卡顿源）
                paint.alpha = (resolvedAlpha * 255).roundToInt().coerceIn(0, 255)
                NinePatchDrawHelper.draw(
                    canvas = canvas.nativeCanvas,
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
            }
            drawContent()
        }
    }
    if (!wholeImageOnly) {
        // 冷缓存异步解码期间：不绘制任何预览，让底色透出，就绪后一次成型
        return this
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

/**
 * 热路径同步解析：位图已在内存缓存时，切分线解析（纯字符串）与 .9.png 引导线探测
 * （结果按源缓存在 loader 内）都能在组合线程完成，返回 null 表示需要异步解码。
 * 这是"设置页返回后容器图逐块卡出、书架回收行图案先大后跳正常"的对症修法：
 * 首帧即拿到九宫格数据，不经历每实例一次的 IO 往返和整图 Crop 预览帧。
 */
private fun resolveNinePatchSync(
    path: String,
    savedNineSlice: String?,
): Pair<Bitmap, ContainerNineSlice>? {
    val bitmap = ReaderTextBackgroundLoader.cached(path) ?: return null
    val slice = parseContainerNineSlice(savedNineSlice)
        ?: ReaderTextBackgroundLoader.nineSliceFractions(path)?.let {
            ContainerNineSlice(it.left, it.right, it.top, it.bottom)
        }
    return slice?.let { bitmap to it }
}

/**
 * 预热背景图内存缓存：在页面首帧前调用一次，让后续 [appContainerBackground] 的同步快路径
 * 直接命中，避免每实例各自异步解码导致的"一块一块卡出来"和"图案先大再跳正常"。
 *
 * 只需在页面可见时触发一次；LRU 缓存会在后台持续命中。书架与设置页各自调用一次即可。
 */
@Composable
fun PreloadContainerBackgrounds(
    bookshelfSettings: BookshelfSettings? = null,
) {
    val themeSettings = LocalAppUiConfiguration.current.theme
    val isDark = LegadoTheme.isDark
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            // 容器大背景图（SplicedColumnGroup 等使用）
            if (themeSettings.enableContainerBackgroundImage) {
                themeSettings.containerBackgroundImagePath(
                    AppContainerBackgroundType.Large, isDark
                )?.takeIf(String::isNotBlank)?.let { path ->
                    ReaderTextBackgroundLoader.load(path)
                    ReaderTextBackgroundLoader.nineSliceFractions(path)
                }
                themeSettings.containerBackgroundImagePath(
                    AppContainerBackgroundType.Item, isDark
                )?.takeIf(String::isNotBlank)?.let { path ->
                    ReaderTextBackgroundLoader.load(path)
                    ReaderTextBackgroundLoader.nineSliceFractions(path)
                }
            }
            // 书架卡片背景图
            bookshelfSettings?.let { settings ->
                val cardImage = if (isDark) {
                    settings.bookshelfCardImageDark
                } else {
                    settings.bookshelfCardImageLight
                }
                cardImage?.takeIf(String::isNotBlank)?.let { path ->
                    ReaderTextBackgroundLoader.load(path)
                    ReaderTextBackgroundLoader.nineSliceFractions(path)
                }
            }
        }
    }
}
