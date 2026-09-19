package io.legado.app.help.highlight

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 九宫格共享几何公式的回归测试：正文渲染层与规则编辑预览都走
 * [NinePatchDrawHelper.layout]，这里锁定两者唯一的口径来源。
 */
class NinePatchDrawHelperLayoutTest {

    @Test
    fun middleBandAnchorsWholeImageScale() {
        // 中带源高 (1-0.2-0.1)*100=70，文字高 40 → 等比 scale=40/70，
        // 四角尺寸 = 源角块 × scale，角块宽高比与源图一致。
        val box = NinePatchDrawHelper.layout(
            0f, 0f, 100f, 40f,
            bitmapWidth = 50f, bitmapHeight = 100f,
            npLeft = 0.2f, npRight = 0.2f, npTop = 0.2f, npBottom = 0.1f,
        )!!
        val scale = 40f / 70f
        assertEquals(0.2f * 50f * scale, box.cornerL, 0.01f)
        assertEquals(0.2f * 100f * scale, box.cornerT, 0.01f)
        assertEquals(0.1f * 100f * scale, box.cornerB, 0.01f)
        assertEquals(-box.cornerL, box.left, 0.01f)
        assertEquals(100f + box.cornerR, box.right, 0.01f)
    }

    @Test
    fun verticalPaddingExpandsMiddleAndScalesCornersUpTogether() {
        // 上下 padding 并入中段拉伸区（文字高 + padTop + padBottom），整张图随
        // 之等比放大——这是「正文调 padding 无效/角块偏小」修复的核心语义。
        val noPad = NinePatchDrawHelper.layout(
            0f, 0f, 100f, 40f,
            bitmapWidth = 50f, bitmapHeight = 100f,
            npLeft = 0.2f, npRight = 0.2f, npTop = 0.2f, npBottom = 0.1f,
        )!!
        val padded = NinePatchDrawHelper.layout(
            0f, 0f, 100f, 40f,
            bitmapWidth = 50f, bitmapHeight = 100f,
            npLeft = 0.2f, npRight = 0.2f, npTop = 0.2f, npBottom = 0.1f,
            padTop = 14f, padBottom = 0f,
        )!!
        val scale = 54f / 70f
        assertEquals(0.2f * 100f * scale, padded.cornerT, 0.01f)
        assertEquals(0.2f * 50f * scale, padded.cornerL, 0.01f)
        // 背景框 = 文字矩形外扩角块 + padding，两侧允许自由溢出。
        assertEquals(-padded.cornerT - 14f, padded.top, 0.01f)
        assertEquals(40f + padded.cornerB, padded.bottom, 0.01f)
        assertEquals(-noPad.cornerL, noPad.left, 0.01f)
    }

    @Test
    fun rawNinePatchBorderIsExcludedFromContentGeometry() {
        val stripped = NinePatchDrawHelper.layout(
            0f, 0f, 100f, 40f,
            bitmapWidth = 50f, bitmapHeight = 100f,
            npLeft = 0.2f, npRight = 0.2f, npTop = 0.2f, npBottom = 0.1f,
        )!!
        val withBorder = NinePatchDrawHelper.layout(
            0f, 0f, 100f, 40f,
            bitmapWidth = 52f, bitmapHeight = 102f,
            npLeft = 0.2f, npRight = 0.2f, npTop = 0.2f, npBottom = 0.1f,
            borderPx = 1f,
        )!!
        assertEquals(stripped.cornerL, withBorder.cornerL, 0.01f)
        assertEquals(stripped.cornerT, withBorder.cornerT, 0.01f)
        assertEquals(stripped.left, withBorder.left, 0.01f)
        assertEquals(stripped.bottom, withBorder.bottom, 0.01f)
    }

    @Test
    fun degenerateTextRectYieldsNoBox() {
        assertEquals(
            null,
            NinePatchDrawHelper.layout(
                0f, 0f, 0f, 40f,
                bitmapWidth = 50f, bitmapHeight = 100f,
                npLeft = 0.2f, npRight = 0.2f, npTop = 0.2f, npBottom = 0.1f,
            ),
        )
    }
}
