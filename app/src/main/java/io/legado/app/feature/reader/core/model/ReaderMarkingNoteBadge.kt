package io.legado.app.feature.reader.core.model

import androidx.compose.runtime.Stable
import kotlin.math.max
import kotlin.math.min

/**
 * 带备注的划线在**末字右下角**的小图标（点击只看备注的入口）。
 *
 * 放在页装饰而不是元素里：绘制与点击命中必须读同一份矩形。角标紧挨着划线末行，
 * 若绘制时重算一次、命中时再算一次，翻页/卷动后两者很容易落到不同位置，于是「看得到
 * 却点不到」或「点空白处弹出别人的备注」。
 *
 * 三条落位口径：
 * - **横向**：贴末字右侧 [GAP_DP]，**不收口**——角标可以探出页边。收口到内容区右边界会
 *   正好压在末字上（划线几乎总是结束在行尾），那是它唯一真正碍事的地方。
 * - **纵向**：与该划线的**下划线居中对齐**（[ReaderUnderline.referenceCenterY]，与画线
 *   同一份 y 口径）；没有下划线的笔记（背景色/字体色）退回行盒竖直中心。
 * - **命中**：24dp 见方的方框，竖向以角标中心对称、横向只在左边多让
 *   [HIT_GROW_LEFT_DP]（见该常量注释），同样不收口。
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
        const val SIZE_DP = 12f

        /** 末字与角标之间的间距（dp）：角标不压在末字上。 */
        const val GAP_DP = 1f

        /** 命中区最小边长（dp）。 */
        const val MIN_HIT_SIZE_DP = 24f

        /**
         * 命中区允许向左多吃的宽度（dp）。
         *
         * 命中区要够大才点得中，但不能以图标为中心左右对称外扩：图标左边 1dp 就是末字，
         * 对称外扩 6dp 会把末字小半圈进触控区，点末字就变成"看备注"而不是"编辑笔记"——
         * 而后者是这个功能必须保留的另一个入口。所以左边只让出这么一点，其余宽度全往
         * 右侧（页边空白）长。
         */
        const val HIT_GROW_LEFT_DP = 4f

        /**
         * 扫本页元素，给每条**有备注**的划线在末字右下角放一个角标。
         *
         * [notes] 是「标记 id → 备注」的当前书快照（见 `ReaderMarkingNoteState`），
         * 空表时直接短路——没有备注的划线在正文里只画线，不出角标。
         *
         * 角标挂在**章节内位置最靠后**的那个元素上：一条划线可以跨行跨页，末行才是它的
         * 收尾处。判定口径与点划线重建选区（`ReaderCanvasSurface` 里按 chapterPosition
         * 排序取首末）一致，两处不会对「这条划线的末行是谁」给出不同答案。
         *
         * [badgeColorOf] 由 Android 侧给（`LegacyReaderPageDecorationFactory`）：颜色要跟随
         * 划线自己的线色/背景色/字体色再压深一档，涉及 `ColorUtils` 的 HSV 运算，不该进
         * `core/model`（JVM 单测里 `android.graphics.Color` 不可用）。
         */
        fun createAll(
            page: ReaderPage,
            notes: Map<String, String>,
            badgeColorOf: (ReaderElement.Text) -> Int,
            density: Float,
        ): List<ReaderMarkingNoteBadge> {
            if (notes.isEmpty() || density <= 0f) return emptyList()
            val lastByMarking = lastMarkedElements(page, notes)
            if (lastByMarking.isEmpty()) return emptyList()
            val size = SIZE_DP * density
            val half = size / 2f
            val hitSize = max(size, MIN_HIT_SIZE_DP * density)
            val gap = GAP_DP * density
            val growLeft = min(hitSize - size, HIT_GROW_LEFT_DP * density)
            return lastByMarking.mapNotNull { (markingId, text) ->
                val line = text.bounds
                if (line.width <= 0f || line.height <= 0f) return@mapNotNull null
                // 纵向与下划线居中；没有下划线（背景色/字体色笔记）就与行盒居中。
                val centerY = text.style.underline?.referenceCenterY(line)
                    ?: (line.top + line.bottom) / 2f
                val left = line.right + gap
                val top = centerY - half
                val hitLeft = left - growLeft
                ReaderMarkingNoteBadge(
                    markingId = markingId,
                    bounds = ReaderRect(
                        left = left,
                        top = top,
                        right = left + size,
                        bottom = top + size,
                    ),
                    // 触控区 = 24dp 见方的方框，左沿在图标左侧 HIT_GROW_LEFT_DP 处，
                    // 其余宽度全在右边（页边空白）；竖向以角标中心对称。
                    // 不收口到页边：探出页边那部分手指点不到，自然不参与命中。
                    hitBounds = ReaderRect(
                        left = hitLeft,
                        top = centerY - hitSize / 2f,
                        right = hitLeft + hitSize,
                        bottom = centerY + hitSize / 2f,
                    ),
                    colorArgb = badgeColorOf(text),
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