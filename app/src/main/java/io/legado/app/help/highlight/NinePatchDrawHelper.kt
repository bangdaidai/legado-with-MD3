package io.legado.app.help.highlight

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.roundToInt

/**
 * 九宫格背景绘制。移植自 readdai `NinePatchHelper`。
 *
 * 参数 [leftX] / [rightX] / [topY] / [bottomY] 均为**图片宽/高的归一化绝对位置** (0~1)：
 *  - leftX / rightX 为两条竖线相对图宽的位置（从左数）
 *  - topY / bottomY 为两条横线相对图高的位置（从上数）
 *  - 允许两条线重合（借 1px 源图当拉伸中心带）
 *
 * 目标角块尺寸优先取 [draw] 的 cornerL/cornerR/cornerT/cornerB（调用方经 [layout]
 * 算出，与文字外扩预留完全一致）；未传时退回按 s = rectH/bh 推导（图片高度贴合目标框）。
 * 目标框放不下四角之和时按比例缩小四角、中段允许为 0，短文字高亮不再压扁角块。
 */
object NinePatchDrawHelper {

    /**
     * 从角块占比转换为绝对线位置（供外部调用简化转换）
     * @param npLeft 左角块占比（0~0.5）
     * @param npRight 右角块占比（0~0.5）
     * @param npTop 上角块占比（0~0.5）
     * @param npBottom 下角块占比（0~0.5）
     * @return Triple(leftX, rightX, topY, bottomY) 绝对线位置
     */
    fun toLinePositions(
        npLeft: Float,
        npRight: Float,
        npTop: Float,
        npBottom: Float,
    ): FloatArray = floatArrayOf(
        npLeft,           // leftX = npLeft（左角块占比 = 左线绝对位置）
        1f - npRight,     // rightX = 1 - npRight（右角块占比转换为右线绝对位置）
        npTop,            // topY = npTop（上角块占比 = 上线绝对位置）
        1f - npBottom,    // bottomY = 1 - npBottom（下角块占比转换为下线绝对位置）
    )

    /**
     * 九宫格背景相对文字矩形的布局结果：
     * box 为背景图目标框（文字矩形外扩四角与 padding），corner* 为四角的目标像素尺寸
     */
    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val cornerL: Float,
        val cornerR: Float,
        val cornerT: Float,
        val cornerB: Float,
    )

    /**
     * 以文字矩形为基准计算背景框（与九宫格切图预览 NineSlicePreview 同一套语义）：
     * 1. 整张图等比缩放，锚点是"中带源高 ((1-npTop-npBottom)×图高) 缩放后恰好
     *    等于 文字高 + 上下 padding"，因此背景框高度 = 图高 × scale，
     *    图案整体比例不变，只有中段做水平拉伸，不会被纵向拉变形
     * 2. 缩放后的角块尺寸 = 源角块 × scale（宽高比天然一致）
     * 3. 背景框 = 文字矩形向外扩角块与 padding
     * 4. 安全回退：若角块总宽超出文字宽度，统一缩小（保持宽高比）
     *
     * 绘制时把返回的角尺寸传给 [draw]，draw 内部只拉伸中段，四角保持缩放后尺寸不变。
     */
    fun layout(
        textLeft: Float,
        textTop: Float,
        textRight: Float,
        textBottom: Float,
        bitmapWidth: Float,
        bitmapHeight: Float,
        npLeft: Float,
        npRight: Float,
        npTop: Float,
        npBottom: Float,
        padStart: Float = 0f,
        padEnd: Float = 0f,
        padTop: Float = 0f,
        padBottom: Float = 0f,
    ): Box? {
        val textW = textRight - textLeft
        val textH = textBottom - textTop
        if (textW <= 0f || textH <= 0f) return null
        if (bitmapWidth <= 0f || bitmapHeight <= 0f) return null

        // 与切图预览一致：中带源高缩放后恰好容下"文字高 + 上下 padding"，
        // 整张图等比缩放（背景框高 = 图高 × scale），只有中段水平拉伸；
        // 两线过近时中带源高夹一个最小值，防止 scale 发散
        val middleSrcH = (1f - npTop - npBottom).coerceIn(0.02f, 1f) * bitmapHeight
        val middleDstH = (textH + padTop + padBottom).coerceAtLeast(1f)
        val scale = middleDstH / middleSrcH
        var cornerL = npLeft * bitmapWidth * scale
        var cornerR = npRight * bitmapWidth * scale
        var cornerT = npTop * bitmapHeight * scale
        var cornerB = npBottom * bitmapHeight * scale

        // 安全回退：角块总宽超出文字宽度时统一缩小（保持宽高比）
        val totalCornerW = cornerL + cornerR
        if (totalCornerW > textW && totalCornerW > 0f) {
            val ratio = textW / totalCornerW
            cornerL *= ratio
            cornerR *= ratio
            cornerT *= ratio
            cornerB *= ratio
        }

        return Box(
            left = textLeft - cornerL - padStart,
            top = textTop - cornerT - padTop,
            right = textRight + cornerR + padEnd,
            bottom = textBottom + cornerB + padBottom,
            cornerL = cornerL,
            cornerR = cornerR,
            cornerT = cornerT,
            cornerB = cornerB,
        )
    }

    fun draw(
        canvas: Canvas,
        bitmap: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        paint: Paint,
        leftX: Float,
        rightX: Float,
        topY: Float,
        bottomY: Float,
        cornerL: Float = 0f,
        cornerR: Float = 0f,
        cornerT: Float = 0f,
        cornerB: Float = 0f,
    ) {
        val rectW = right - left
        val rectH = bottom - top
        if (rectW <= 0f || rectH <= 0f) return

        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        if (bw <= 0f || bh <= 0f) return

        // 归一化线位置，各自夹紧范围；并保证 leftX<=rightX、topY<=bottomY（允许重合）
        val lx0 = leftX.coerceIn(0.02f, 0.98f)
        val rx0 = rightX.coerceIn(0.02f, 0.98f)
        val ty0 = topY.coerceIn(0.02f, 0.98f)
        val by0 = bottomY.coerceIn(0.02f, 0.98f)
        val lxN = lx0.coerceAtMost(rx0)
        val rxN = lx0.coerceAtLeast(rx0)
        val tyN = ty0.coerceAtMost(by0)
        val byN = ty0.coerceAtLeast(by0)

        // 目标角块尺寸：优先用调用方预留的尺寸；未传时退回按目标框高度贴合缩放推导
        val s = rectH / bh
        val wLsrc = lxN * bw
        val wRsrc = (1f - rxN) * bw
        val hTsrc = tyN * bh
        val hBsrc = (1f - byN) * bh
        var wL = if (cornerL > 0f) cornerL else wLsrc * s
        var wR = if (cornerR > 0f) cornerR else wRsrc * s
        var hT = if (cornerT > 0f) cornerT else hTsrc * s
        var hB = if (cornerB > 0f) cornerB else hBsrc * s

        // 目标框放不下四角之和时按比例缩小四角（同 TextLine.drawNinePatchBitmap 的处理），
        // 否则中段坐标反转，短文字高亮时角块会被压扁
        if (wL + wR > rectW && wL + wR > 0f) {
            val ratio = rectW / (wL + wR)
            wL *= ratio
            wR *= ratio
        }
        if (hT + hB > rectH && hT + hB > 0f) {
            val ratio = rectH / (hT + hB)
            hT *= ratio
            hB *= ratio
        }
        val wM = (rectW - wL - wR).coerceAtLeast(0f)
        val hM = (rectH - hT - hB).coerceAtLeast(0f)

        val x0 = left
        val x1 = left + wL
        val x2 = right - wR
        val x3 = right
        val y0 = top
        val y1 = top + hT
        val y2 = bottom - hB
        val y3 = bottom

        val bwI = bw.toInt()
        val bhI = bh.toInt()
        val sxLi = wLsrc.roundToInt().coerceIn(0, bwI)
        val sxR = rxN * bw
        val sxTi = hTsrc.roundToInt().coerceIn(0, bhI)
        val sxB = byN * bh
        // 两条线重合时中带 src 宽为 0，借 1px 作为可拉伸中心带，避免空白
        val sxRi = if (rxN > lxN) sxR.roundToInt().coerceIn(0, bwI)
        else (lxN * bw + 1f).roundToInt().coerceIn(0, bwI)
        val sxBii = if (byN > tyN) sxB.roundToInt().coerceIn(0, bhI)
        else (tyN * bh + 1f).roundToInt().coerceIn(0, bhI)

        val srcRects = arrayOf(
            Rect(0, 0, sxLi, sxTi),
            Rect(sxLi, 0, sxRi, sxTi),
            Rect(sxRi, 0, bwI, sxTi),
            Rect(0, sxTi, sxLi, sxBii),
            Rect(sxLi, sxTi, sxRi, sxBii),
            Rect(sxRi, sxTi, bwI, sxBii),
            Rect(0, sxBii, sxLi, bhI),
            Rect(sxLi, sxBii, sxRi, bhI),
            Rect(sxRi, sxBii, bwI, bhI)
        )

        val dstRects = arrayOf(
            RectF(x0, y0, x1, y1),
            RectF(x1, y0, x2, y1),
            RectF(x2, y0, x3, y1),
            RectF(x0, y1, x1, y2),
            RectF(x1, y1, x2, y2),
            RectF(x2, y1, x3, y2),
            RectF(x0, y2, x1, y3),
            RectF(x1, y2, x2, y3),
            RectF(x2, y2, x3, y3)
        )

        // 裁切到目标矩形：避免极小高亮框里角块相互覆盖
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        for (i in 0 until 9) {
            val src = srcRects[i]
            if (src.width() <= 0 || src.height() <= 0) continue
            canvas.drawBitmap(bitmap, src, dstRects[i], paint)
        }
        canvas.restore()
    }
}
