package io.legado.app.ui.widget.components.dialog

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme
import kotlin.math.roundToInt

/** 放大镜直径与放大倍率：镜内显示 1/3 屏幕尺寸的区域 */
private val LoupeSize = 96.dp
private const val LoupeZoom = 3f

/**
 * 全屏取色覆盖层：展示一张预先抓好的窗口冻结快照，用户在快照上拖动取色，
 * 确认后把颜色交回调色弹层定稿——胶囊上的确认即最终确认，
 * 调用方无需再让用户手动保存一次。
 *
 * 快照由调用方用 PixelCopy 从 Activity 窗口抓取——取色弹层自身跑在独立窗口，
 * 不会出现在快照里，因此这里展示的就是弹层背后未被遮挡的真实页面，
 * 坐标按快照与覆盖层的尺寸比例映射即可。
 */
@Composable
fun EyedropperOverlay(
    snapshot: Bitmap,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val imageBitmap = remember { snapshot.asImageBitmap() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        var rootSize by remember { mutableStateOf(IntSize.Zero) }
        // 手指位置（覆盖层像素坐标）与其对应的快照像素坐标
        var touchPoint by remember { mutableStateOf<Offset?>(null) }
        var snapshotPoint by remember { mutableStateOf<IntOffset?>(null) }
        var pickedColor by remember { mutableStateOf<Color?>(null) }

        val density = LocalDensity.current
        val loupeSizePx = with(density) { LoupeSize.toPx() }
        val loupeGapPx = with(density) { 12.dp.toPx() }

        fun sampleAt(point: Offset) {
            if (rootSize.width <= 0 || rootSize.height <= 0) return
            val x = (point.x / rootSize.width * snapshot.width).roundToInt()
                .coerceIn(0, snapshot.width - 1)
            val y = (point.y / rootSize.height * snapshot.height).roundToInt()
                .coerceIn(0, snapshot.height - 1)
            touchPoint = point
            snapshotPoint = IntOffset(x, y)
            pickedColor = Color(snapshot.getPixel(x, y))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { rootSize = it }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = ::sampleAt,
                        onDrag = { change, _ ->
                            change.consume()
                            sampleAt(change.position)
                        },
                    )
                }
        ) {
            Image(
                bitmap = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )

            val color = pickedColor
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 24.dp),
            ) {
                if (color == null) {
                    Text(
                        text = stringResource(R.string.color_eyedropper_hint),
                        fontSize = 14.sp,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(LegadoTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(LegadoTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f))
                        .padding(horizontal = 4.dp),
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel),
                            tint = LegadoTheme.colorScheme.onSurface,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(color ?: Color.Transparent)
                            .border(1.dp, LegadoTheme.colorScheme.outlineVariant, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = color?.toArgb()
                            ?.let { "#${Integer.toHexString(it).uppercase().padStart(8, '0')}" }
                            ?: "——",
                        fontSize = 14.sp,
                        color = LegadoTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { color?.let { onConfirm(it.toArgb()) } },
                        enabled = color != null,
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.confirm),
                            tint = LegadoTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            val touch = touchPoint
            if (touch != null && rootSize.width > 0 && rootSize.height > 0) {
                // 放大镜默认悬在手指上方，顶部放不下时翻到手指下方；整体夹在屏幕内
                var centerX = touch.x
                var centerY = touch.y - loupeSizePx / 2 - loupeGapPx
                if (centerY - loupeSizePx / 2 < 0f) {
                    centerY = touch.y + loupeSizePx / 2 + loupeGapPx
                }
                centerX = centerX.coerceIn(loupeSizePx / 2, rootSize.width - loupeSizePx / 2)
                centerY = centerY.coerceIn(loupeSizePx / 2, rootSize.height - loupeSizePx / 2)

                val focusColor = LegadoTheme.colorScheme.primary
                Canvas(
                    modifier = Modifier
                        .size(LoupeSize)
                        .offset {
                            IntOffset(
                                (centerX - loupeSizePx / 2).roundToInt(),
                                (centerY - loupeSizePx / 2).roundToInt(),
                            )
                        }
                ) {
                    drawLoupe(
                        imageBitmap = imageBitmap,
                        center = snapshotPoint ?: IntOffset.Zero,
                        scale = snapshot.width.toFloat() / rootSize.width,
                        focusColor = focusColor,
                    )
                }
            }
        }
    }
}

/** 从快照中取放大镜中心附近 1/zoom 区域放大绘制，叠加白圈描边与主题色取色十字线 */
private fun DrawScope.drawLoupe(
    imageBitmap: ImageBitmap,
    center: IntOffset,
    scale: Float,
    focusColor: Color,
) {
    val radius = size.minDimension / 2f
    val srcHalf = (radius / LoupeZoom * scale).roundToInt().coerceAtLeast(1)
    val srcSize = IntSize(srcHalf * 2, srcHalf * 2)
    val srcOffset = IntOffset(
        (center.x - srcHalf).coerceIn(0, (imageBitmap.width - srcSize.width).coerceAtLeast(0)),
        (center.y - srcHalf).coerceIn(0, (imageBitmap.height - srcSize.height).coerceAtLeast(0)),
    )
    val circleClip = Path().apply {
        addOval(Rect(0f, 0f, size.width, size.height))
    }
    clipPath(circleClip) {
        drawImage(
            image = imageBitmap,
            srcOffset = srcOffset,
            srcSize = srcSize,
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.High,
        )
    }
    val ringWidth = 3.dp.toPx()
    drawCircle(
        color = Color.White,
        radius = radius - ringWidth / 2,
        style = Stroke(width = ringWidth),
    )
    val hairline = 1.dp.toPx()
    drawCircle(
        color = Color.Black.copy(alpha = 0.35f),
        radius = radius - ringWidth - hairline / 2,
        style = Stroke(width = hairline),
    )
    val cross = 6.dp.toPx()
    val stroke = 1.5.dp.toPx()
    val cx = size.width / 2
    val cy = size.height / 2
    drawLine(
        color = focusColor,
        start = Offset(cx - cross, cy),
        end = Offset(cx + cross, cy),
        strokeWidth = stroke,
    )
    drawLine(
        color = focusColor,
        start = Offset(cx, cy - cross),
        end = Offset(cx, cy + cross),
        strokeWidth = stroke,
    )
}
