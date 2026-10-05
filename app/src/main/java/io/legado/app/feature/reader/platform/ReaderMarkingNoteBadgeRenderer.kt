package io.legado.app.feature.reader.platform

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import io.legado.app.feature.reader.core.model.ReaderMarkingNoteBadge

/**
 * 笔记角标的绘制：**实心圆 + 中间三个白点**。
 *
 * 圆用角标自己的颜色（跟随划线色再压深一档，见
 * `LegacyReaderPageDecorationFactory.markingNoteBadgeColor`），三个点是纯白：
 * 白点与被压深后的角标色之间有足够对比，而白色是常量，不需要渲染层知道背后的底色
 * ——正文可能压在背景图上，也可能日夜各不同，没有一个可靠的"底色"可用。
 * 角标色已强制不透明（`stripAlpha`），所以白点在任何标记色上都不会被"透"掉。
 *
 * 形状就三个 `drawCircle`，不引矢量资源；Paint 与点坐标都是共享常量，页内多条带备注的
 * 划线同帧画也只是改一次颜色。
 */
object ReaderMarkingNoteBadgeRenderer {
    /** 坐标按 24 x 24 视口给，绘制时缩放到角标实际大小。 */
    private const val VIEWPORT = 24f

    private const val CENTER = 12f

    /** 实心圆半径：留一点余量给抗锯齿，圆面不顶到角标包围盒边缘。 */
    private const val DISC_RADIUS = 10.4f

    /** 三个点的横向位置与半径。点距 5.2、点径 3.4，间隙 1.8——再近就糊成一条白杠。 */
    private val DOT_X = floatArrayOf(6.8f, 12f, 17.2f)
    private const val DOT_RADIUS = 1.7f

    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    fun draw(canvas: Canvas, badge: ReaderMarkingNoteBadge) {
        val bounds = badge.bounds
        if (bounds.width <= 0f || bounds.height <= 0f) return
        val checkpoint = canvas.save()
        try {
            canvas.translate(bounds.left, bounds.top)
            canvas.scale(bounds.width / VIEWPORT, bounds.height / VIEWPORT)
            discPaint.color = badge.colorArgb
            canvas.drawCircle(CENTER, CENTER, DISC_RADIUS, discPaint)
            for (x in DOT_X) {
                canvas.drawCircle(x, CENTER, DOT_RADIUS, dotPaint)
            }
        } finally {
            canvas.restoreToCount(checkpoint)
        }
    }
}