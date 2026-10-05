package io.legado.app.feature.reader.platform

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import io.legado.app.feature.reader.core.model.ReaderMarkingNoteBadge

/**
 * 笔记角标的绘制。形状与颜色都定死在一份共享 Path/Paint 里：
 * 角标每帧要画（页内可能同时有好几条带备注的划线），不能每帧新建对象。
 *
 * 形如对话气泡：正文末行右下角一个实心圆加小尾巴。9dp 见方画不出更多细节，
 * 再多的笔画只会糊成一团，因此只用「圆 + 尾」这两笔可辨认的轮廓。
 */
object ReaderMarkingNoteBadgeRenderer {
    /** 24 x 24 视口，与 [icon] 的坐标同尺度。 */
    private const val VIEWPORT = 24f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 尾巴画在圆的左下方：角标贴行末右缘，气泡看起来才是「从文字里冒出来的」。 */
    private val tail = Path().apply {
        moveTo(6.6f, 13.4f)
        lineTo(5.2f, 21.6f)
        lineTo(12.9f, 14.9f)
        close()
    }

    fun draw(canvas: Canvas, badge: ReaderMarkingNoteBadge) {
        val checkpoint = canvas.save()
        try {
            canvas.translate(badge.bounds.left, badge.bounds.top)
            canvas.scale(badge.bounds.width / VIEWPORT, badge.bounds.height / VIEWPORT)
            paint.color = badge.colorArgb
            canvas.drawCircle(12f, 10.2f, 7.6f, paint)
            canvas.drawPath(tail, paint)
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }
}