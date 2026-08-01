package io.legado.app.help.highlight

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.min

/**
 * 九宫格背景绘制。
 *
 * 角块按 contain 系数（`min(rectW/bw, rectH/bh)`）统一等比缩放，中段用剩余空间拉伸，
 * 保证同一模板在不同分辨率原图下视觉一致；全部 9 块在同一 clipRect 内绘制，不会溢出目标矩形。
 */
object NinePatchDrawHelper {

    private val srcRect = Rect()
    private val dstRect = RectF()

    /**
     * @param leftFrac 左侧分割线占图宽比例
     * @param rightFrac 右侧分割线占图宽比例（自右起算）
     * @param topFrac 上侧分割线占图高比例
     * @param bottomFrac 下侧分割线占图高比例（自下起算）
     */
    fun draw(
        canvas: Canvas,
        bitmap: Bitmap,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        leftFrac: Float,
        rightFrac: Float,
        topFrac: Float,
        bottomFrac: Float,
        paint: Paint,
    ) {
        val rectW = right - left
        val rectH = bottom - top
        if (rectW <= 0f || rectH <= 0f) return

        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        if (bw <= 0f || bh <= 0f) return

        // 源图上的分割位置（像素）。允许不对称切分，仅保证不越过对侧
        val srcL = (bw * leftFrac.coerceIn(0f, 0.98f))
        val srcR = (bw * rightFrac.coerceIn(0f, 0.98f))
        val srcT = (bh * topFrac.coerceIn(0f, 0.98f))
        val srcB = (bh * bottomFrac.coerceIn(0f, 0.98f))

        // contain 缩放：四方向角块用同一系数，避免高分辨率原图角块过宽
        val scale = min(rectW / bw, rectH / bh)
        val dstL = srcL * scale
        val dstR = srcR * scale
        val dstT = srcT * scale
        val dstB = srcB * scale
        val dstM = (rectW - dstL - dstR).coerceAtLeast(0f)
        val dstV = (rectH - dstT - dstB).coerceAtLeast(0f)

        // 源图纵横分割坐标
        val sx0 = 0
        val sx1 = srcL.toInt().coerceIn(0, bitmap.width)
        val sx2 = (bw - srcR).toInt().coerceIn(sx1, bitmap.width)
        val sx3 = bitmap.width
        val sy0 = 0
        val sy1 = srcT.toInt().coerceIn(0, bitmap.height)
        val sy2 = (bh - srcB).toInt().coerceIn(sy1, bitmap.height)
        val sy3 = bitmap.height

        // 目标纵横分割坐标
        val dx0 = left
        val dx1 = left + dstL
        val dx2 = dx1 + dstM
        val dx3 = right
        val dy0 = top
        val dy1 = top + dstT
        val dy2 = dy1 + dstV
        val dy3 = bottom

        val sxs = intArrayOf(sx0, sx1, sx2, sx3)
        val sys = intArrayOf(sy0, sy1, sy2, sy3)
        val dxs = floatArrayOf(dx0, dx1, dx2, dx3)
        val dys = floatArrayOf(dy0, dy1, dy2, dy3)

        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                val sl = sxs[col]
                val sr = sxs[col + 1]
                val st = sys[row]
                val sb = sys[row + 1]
                if (sr <= sl || sb <= st) continue
                val dl = dxs[col]
                val dr = dxs[col + 1]
                val dt = dys[row]
                val db = dys[row + 1]
                if (dr <= dl || db <= dt) continue
                srcRect.set(sl, st, sr, sb)
                dstRect.set(dl, dt, dr, db)
                canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            }
        }
        canvas.restore()
    }
}
