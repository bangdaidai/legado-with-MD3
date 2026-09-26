package io.legado.app.ui.widget.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import java.io.File

/**
 * 九宫格切图编辑器：从 HighlightRuleEditSheet 抽出，供高亮背景图与容器背景图共用。
 *
 * [initialLeft]/[initialRight]/[initialTop]/[initialBottom] 与 onSave 回调均为
 * 「从左/上数的绝对线位置（0~1）」；角块占比(np*)与线位置的换算由调用方负责。
 */
@Composable
fun NinePatchEditorDialog(
    show: Boolean,
    imagePath: String,
    initialLeft: Float,
    initialRight: Float,
    initialTop: Float,
    initialBottom: Float,
    onDismissRequest: () -> Unit,
    onSave: (left: Float, right: Float, top: Float, bottom: Float) -> Unit,
) {
    var left by remember(show, imagePath) { mutableFloatStateOf(initialLeft) }
    var right by remember(show, imagePath) { mutableFloatStateOf(initialRight) }
    var top by remember(show, imagePath) { mutableFloatStateOf(initialTop) }
    var bottom by remember(show, imagePath) { mutableFloatStateOf(initialBottom) }

    val bitmap = remember(imagePath) {
        runCatching {
            val file = File(imagePath)
            if (file.exists()) BitmapFactory.decodeFile(imagePath) else null
        }.getOrNull()
    }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.nine_patch_editor),
        endAction = {
            MediumTonalButton(
                onClick = { onSave(left, right, top, bottom) },
                icon = Icons.Default.Done,
                contentDescription = stringResource(R.string.save),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // 切分卡片：长图限高 letterbox 居中显示
            if (bitmap != null) {
                var imageRect by remember { mutableStateOf(Rect.Zero) }
                val splitLineColor = MaterialTheme.colorScheme.primary
                NormalCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    cornerRadius = 12.dp,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.foundation.layout.BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth(0.8f),
                        ) {
                            // 长图高度封顶后由 Canvas 做 contain 居中，避免细长一条没法拖
                            // 设置最小高度和最小宽度，确保细长条图片也能拖动四根线并看到效果
                            val boxHeight = minOf(
                                280.dp,
                                maxWidth * (bitmap.height.toFloat() / bitmap.width),
                            ).coerceAtLeast(100.dp)
                            val boxWidth = minOf(
                                maxWidth,
                                maxHeight * (bitmap.width.toFloat() / bitmap.height),
                            ).coerceAtLeast(120.dp)
                            Box(
                                modifier = Modifier
                                    .width(boxWidth)
                                    .height(boxHeight),
                            ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                // 按下时锁定最近的线，拖动期间只更新锁定目标
                                var dragTarget by mutableIntStateOf(-1)
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        val ir = imageRect
                                        if (ir.width <= 0f || ir.height <= 0f) { dragTarget = -1; return@detectDragGestures }
                                        val relX = (offset.x - ir.left) / ir.width
                                        val relY = (offset.y - ir.top) / ir.height
                                        // 四条线的绝对位置（left/right 都是从左数，top/bottom 都是从上数）
                                        val dL = kotlin.math.abs(relX - left)
                                        val dR = kotlin.math.abs(relX - right)
                                        val dT = kotlin.math.abs(relY - top)
                                        val dB = kotlin.math.abs(relY - bottom)
                                        // 竖线取最近的一条、横线取最近的一条
                                        val bestV = if (dL <= dR) Pair(0, dL) else Pair(1, dR)
                                        val bestH = if (dT <= dB) Pair(2, dT) else Pair(3, dB)
                                        dragTarget = if (bestV.second <= bestH.second) {
                                            if (bestV.second <= 0.15f) bestV.first else -1
                                        } else {
                                            if (bestH.second <= 0.15f) bestH.first else -1
                                        }
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        if (dragTarget == -1) return@detectDragGestures
                                        val ir = imageRect
                                        if (ir.width <= 0f || ir.height <= 0f) return@detectDragGestures
                                        val relX = ((change.position.x - ir.left) / ir.width).coerceIn(0.02f, 0.98f)
                                        val relY = ((change.position.y - ir.top) / ir.height).coerceIn(0.02f, 0.98f)
                                        when (dragTarget) {
                                            // 线位置约束：leftX <= rightX, topY <= bottomY
                                            0 -> left = relX.coerceAtMost(right - 0.02f)
                                            1 -> right = relX.coerceAtLeast(left + 0.02f)
                                            2 -> top = relY.coerceAtMost(bottom - 0.02f)
                                            3 -> bottom = relY.coerceAtLeast(top + 0.02f)
                                        }
                                    },
                                    onDragEnd = { dragTarget = -1 },
                                    onDragCancel = { dragTarget = -1 },
                                )
                            }
                    ) {
                        // contain 适配：图片在框内等比居中，命中与线坐标都基于实际显示矩形
                        val canvasW = size.width
                        val canvasH = size.height
                        val scale = minOf(canvasW / bitmap.width, canvasH / bitmap.height)
                        val drawW = bitmap.width * scale
                        val drawH = bitmap.height * scale
                        val offX = (canvasW - drawW) / 2f
                        val offY = (canvasH - drawH) / 2f
                        val ir = Rect(offX, offY, offX + drawW, offY + drawH)
                        imageRect = ir

                        drawImage(
                            image = bitmap.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(ir.left.toInt(), ir.top.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(drawW.toInt(), drawH.toInt()),
                        )

                        val lineColor = splitLineColor
                        val lineWidth = 2.dp.toPx()
                        // left/right/top/bottom 都是从左/上数的绝对位置(0~1)
                        val lx = ir.left + ir.width * left
                        val rx = ir.left + ir.width * right
                        val ty = ir.top + ir.height * top
                        val by2 = ir.top + ir.height * bottom
                        drawLine(lineColor, Offset(lx, ir.top), Offset(lx, ir.bottom), lineWidth)
                        drawLine(lineColor, Offset(rx, ir.top), Offset(rx, ir.bottom), lineWidth)
                        drawLine(lineColor, Offset(ir.left, ty), Offset(ir.right, ty), lineWidth)
                        drawLine(lineColor, Offset(ir.left, by2), Offset(ir.right, by2), lineWidth)
                        // 中间矩形（可拉伸区）描边
                        val minX = minOf(lx, rx); val maxX = maxOf(lx, rx)
                        val minY = minOf(ty, by2); val maxY = maxOf(ty, by2)
                        drawRect(
                            color = lineColor.copy(alpha = 0.3f),
                            topLeft = Offset(minX, minY),
                            size = androidx.compose.ui.geometry.Size(maxX - minX, maxY - minY),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                            }
                        }
                        }
                        }
                Text(
                    text = "拖动线条调整切分位置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                NineSlicePreview(
                    imagePath = imagePath,
                    npLeft = left,
                    npRight = 1f - right,
                    npTop = top,
                    npBottom = 1f - bottom,
                )
            }
        }
    }
}

/**
 * 九宫格预览：背景图铺占大部分区域，中央虚线框代表文字，直观表达"背景包住文字"
 */
@Composable
private fun NineSlicePreview(
    imagePath: String,
    npLeft: Float,
    npRight: Float,
    npTop: Float,
    npBottom: Float,
) {
    val bitmap = remember(imagePath) {
        runCatching {
            val file = File(imagePath)
            if (file.exists()) BitmapFactory.decodeFile(imagePath) else null
        }.getOrNull()
    }
    if (bitmap == null) return

    NormalCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        cornerRadius = 12.dp,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        val canvasW = size.width
        val canvasH = size.height
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        if (bw <= 0f || bh <= 0f) return@Canvas

        // 按高度等比缩放，宽度给最小值保证窄图也能看清九宫格效果
        val maxH = canvasH * 0.84f
        val fitScale = maxH / bh
        val minW = canvasW * 0.6f
        var bgW = (bw * fitScale).coerceAtLeast(minW)
        val bgH = bh * fitScale
        val bgLeft = (canvasW - bgW) / 2f
        val bgTop = (canvasH - bgH) / 2f
        val bgRight = bgLeft + bgW
        val bgBottom = bgTop + bgH

        // 角块按高度缩放（与 layout() 一致），宽度拉伸不影响角块
        var cornerL = npLeft * bw * fitScale
        var cornerR = npRight * bw * fitScale
        var cornerT = npTop * bh * fitScale
        var cornerB = npBottom * bh * fitScale
        // 安全回退：角块总宽超出图片宽度时统一缩小（保持宽高比）
        val totalCornerW = cornerL + cornerR
        if (totalCornerW > bgW && totalCornerW > 0f) {
            val ratio = bgW / totalCornerW
            cornerL *= ratio
            cornerR *= ratio
            cornerT *= ratio
            cornerB *= ratio
        }
        val textLeft = bgLeft + cornerL
        val textRight = bgRight - cornerR
        val textTop = bgTop + cornerT
        val textBottom = bgBottom - cornerB

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        io.legado.app.help.highlight.NinePatchDrawHelper.draw(
            drawContext.canvas.nativeCanvas, bitmap,
            bgLeft, textTop - cornerT, bgRight, textBottom + cornerB,
            paint,
            leftX = npLeft, rightX = 1f - npRight,
            topY = npTop, bottomY = 1f - npBottom,
            cornerL, cornerR, cornerT, cornerB,
        )
        // 文字行虚线：正好落在九宫格中段拉伸区内
        drawRect(
            color = Color(0x66000000),
            topLeft = Offset(textLeft, textTop),
            size = androidx.compose.ui.geometry.Size(textRight - textLeft, textBottom - textTop),
            style = Stroke(width = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
        )
    }
    }
}
