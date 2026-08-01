package io.legado.app.help.highlight

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * 九宫格背景绘制。移植自 readdai `NinePatchHelper`。
 *
 * 参数 [leftX] / [rightX] / [topY] / [bottomY] 均为**图片宽/高的归一化绝对位置** (0~1)：
 *  - leftX / rightX 为两条竖线相对图宽的位置
 *  - topY / bottomY 为两条横线相对图高的位置
 *  - 允许两条线重合（借 1px 源图当拉伸中心带）
 *
 * 四角按目标框高度贴合缩放 s = rectH/bh，绝不变形；
 * 中段沿水平/垂直方向拉伸填满剩余空间。
 */
object NinePatchDrawHelper {

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

        // 四角按目标框高度贴合缩放：横向文字行以行高为基准，水平中段拉伸铺满宽度。
        // 不用 min(rectW/bw, rectH/bh)（contain 整图），否则命中文字较短时四角会被缩成细线，
        // 观感上"背景图很小盖不住文字"。
        val s = rectH / bh

        val wLsrc = lxN * bw
        val wRsrc = (1f - rxN) * bw
        val hTsrc = tyN * bh
        val hBsrc = (1f - byN) * bh

        val wL = wLsrc * s
        val wR = wRsrc * s
        val hT = hTsrc * s
        val hB = hBsrc * s
        val wM = (rectW - wL - wR).let { if (it > 0f) it else 0f }
        val hM = (rectH - hT - hB).let { if (it > 0f) it else 0f }

        val x0 = left
        val x1 = left + wL
        val x2 = left + wL + wM
        val x3 = right
        val y0 = top
        val y1 = top + hT
        val y2 = top + hT + hM
        val y3 = bottom

        val sxLi = wLsrc.toInt().coerceAtLeast(0)
        val sxR = rxN * bw
        val sxTi = hTsrc.toInt().coerceAtLeast(0)
        val sxB = byN * bh
        val bwI = bw.toInt()
        val bhI = bh.toInt()
        // 两条线重合时中带 src 宽为 0，借 1px 作为可拉伸中心带，避免空白
        val sxRi = if (rxN > lxN) sxR.toInt().coerceAtLeast(0)
        else (lxN * bw + 1f).toInt().coerceAtMost(bwI)
        val sxBii = if (byN > tyN) sxB.toInt().coerceAtLeast(0)
        else (tyN * bh + 1f).toInt().coerceAtMost(bhI)

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
