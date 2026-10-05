package io.legado.app.feature.reader.core.model

import androidx.compose.runtime.Stable
import kotlin.math.max
import kotlin.math.min

/**
 * 带备注的划线在**末行右下角**的小图标（点击只看备注的入口）。
 *
 * 放在页装饰而不是元素里：绘制与点击命中必须读同一份矩形。角标压在划线末行的包围盒内，
 * 若绘制时重算一次、命中时再算一次，翻页/卷动后两者很容易落到不同位置，于是「看得到
 * 却点不到」或「点空白处弹出别人的备注」。
 *
 * [bounds] 是绘制包围盒，[hitBounds] 只在触控维度上外扩：视觉必须小（9dp 才不遮正文），
 * 手指却点不中 9dp，所以命中区至少 [MIN_HIT_SIZE_DP] 见方，且以外扩后仍落在页内为准。
 */
@Stable
data class ReaderMarkingNoteBadge(
    val markingId: String,
    val bounds: ReaderRect,
    val hitBounds: ReaderRect,
    val colorArgb: Int,
) {
    fun hitTest(x: Float, y: Float): Boolean = hitBounds.contains(x, y)

    companion object {
        /** 视觉直径（dp）。 */
        const val SIZE_DP = 9f

        /** 命中区最小边长（dp）：视觉 9dp 直接当命中区几乎点不中。 */
        const val MIN_HIT_SIZE_DP = 24f

        /** 末字右侧的让位（dp）：正文右边还有空档时角标落在空档里，不压字。 */
        private const val TRAILING_GAP_DP = 1.5f

        /**
         * 扫本页元素，给每条**有备注**的划线在末行右下角放一个角标。
         *
         * [notes] 是「标记 id → 备注」的当前书快照（见 `ReaderMarkingNoteState`），
         * 空表时直接短路——没有备注的划线在正文里只画线，不出角标。
         *
         * 角标挂在**章节内位置最靠后**的那个元素上：一条划线可以跨行跨页，末行才是它的
         * 收尾处。判定口径与点划线重建选区（`ReaderCanvasSurface` 里按 chapterPosition
         * 排序取首末）一致，两处不会对「这条划线的末行是谁」给出不同答案。
         */
        fun createAll(
            page: ReaderPage,
            notes: Map<String, String>,
            accentColorArgb: Int,
            density: Float,
        ): List<ReaderMarkingNoteBadge> {
            if (notes.isEmpty() || density <= 0f) return emptyList()
            val lastByMarking = lastMarkedElements(page, notes)
            if (lastByMarking.isEmpty()) return emptyList()
            val size = SIZE_DP * density
            val hitSize = max(size, MIN_HIT_SIZE_DP * density)
            val gap = TRAILING_GAP_DP * density
            val pageBottom = max(page.heightPx.toFloat(), page.scrollExtentPx)
            // 竖向收口只能对页自身的范围：连续卷动页是整章（`heightPx` 只是视口高、
            // `contentBottomPx` 恒为视口下沿），按内容区下沿收口会把第一屏以下的角标
            // 全钉在视口底边。
            return lastByMarking.mapNotNull { (markingId, text) ->
                val line = text.bounds
                if (line.width <= 0f || line.height <= 0f) return@mapNotNull null
                // 横向相反：正文右边（contentRightPx）在两种模式下都是同一道边，
                // 末字右边还有空档就落在空档里，贴到内容边为止；否则退回压在末字右下角
                // （行盒下沿本就是降部留白，压上去也只盖住字形的一角）。
                val right = min(line.right + gap, page.contentRightPx)
                val bottom = min(line.bottom, pageBottom)
                if (right <= 0f || bottom <= 0f) return@mapNotNull null
                val bounds = ReaderRect(
                    left = right - size,
                    top = bottom - size,
                    right = right,
                    bottom = bottom,
                )
                val centerX = bounds.left + size / 2f
                val centerY = bounds.top + size / 2f
                val hitLeft = (centerX - hitSize / 2f).coerceAtLeast(0f)
                val hitTop = (centerY - hitSize / 2f).coerceAtLeast(0f)
                ReaderMarkingNoteBadge(
                    markingId = markingId,
                    bounds = bounds,
                    hitBounds = ReaderRect(
                        left = hitLeft,
                        top = hitTop,
                        right = min(hitLeft + hitSize, page.widthPx.toFloat()),
                        bottom = min(hitTop + hitSize, pageBottom),
                    ),
                    colorArgb = text.style.underline?.colorArgb
                        ?: text.style.backgroundArgb
                        ?: accentColorArgb,
                )
            }
        }

        /** 有备注的划线 → 该划线在本页的最靠后元素（按章节内位置，元素顺序即阅读序）。 */
        private fun lastMarkedElements(
            page: ReaderPage,
            notes: Map<String, String>,
        ): Map<String, ReaderElement.Text> {
            val last = LinkedHashMap<String, ReaderElement.Text>()
            page.elements.forEach { element ->
                if (element !is ReaderElement.Text) return@forEach
                val markingId = element.markingId
                if (markingId.isNullOrEmpty()) return@forEach
                if (notes[markingId].isNullOrBlank()) return@forEach
                val previous = last[markingId]
                if (previous == null || element.chapterPosition > previous.chapterPosition) {
                    last[markingId] = element
                }
            }
            return last
        }
    }
}

/** 命中本页某个笔记角标；同一坐标只可能命中一个（角标互不重叠），取最后一个即可。 */
fun ReaderPage.markingNoteBadgeAt(x: Float, y: Float): ReaderMarkingNoteBadge? =
    decoration.markingNoteBadges.lastOrNull { it.hitTest(x, y) }