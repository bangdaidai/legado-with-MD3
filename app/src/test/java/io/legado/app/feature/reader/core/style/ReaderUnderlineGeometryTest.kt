package io.legado.app.feature.reader.core.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下划线几何是正文、选中预览、规则预览三处共用的唯一来源，这里钉死它对外承诺的性质。
 *
 * 回归背景：高亮规则预览曾经自带一套波浪/虚线/双线数字（波长 16dp、峰高 2.5dp、
 * 虚线末尾截断、双线上下对称），与正文的 24dp / 1.5dp / 均摊 / 单侧分布不一致，
 * 于是「预览调好了、正文不一样」。这些断言就是防止有人再写第二份。
 */
class ReaderUnderlineGeometryTest {

    @Test
    fun `half wave count rounds and stays at least one`() {
        assertEquals(2, waveHalfWaveCount(23f, 12f))
        // 比一个半波还短时退化成 1 个，而不是 0 个导致下一步除零
        assertEquals(1, waveHalfWaveCount(5f, 12f))
        // 退化配置（半波长 0）不能除零：钳到最小段长后仍是有限正数
        assertTrue(waveHalfWaveCount(100f, 0f).isFinite())
        assertTrue(waveHalfWaveCount(100f, 0f) >= 1)
    }

    @Test
    fun `half waves spread the remainder instead of leaving a squeezed tail`() {
        val waves = waveHalfWaves(0f, 23f, halfWavePx = 12f, controlOffsetPx = 3f)

        // 23 / 12 = 1.92 → 2 个半波，各 11.5，余数摊匀后没有挤扁的尾波
        assertEquals(2, waves.size)
        assertEquals(0f, waves.first().startX, 0f)
        assertEquals(23f, waves.last().endX, 0f)
        waves.zipWithNext().forEach { (a, b) ->
            assertEquals(11.5f, b.startX - a.startX, 1e-4f)
        }
    }

    @Test
    fun `half waves alternate control offset around the baseline`() {
        val waves = waveHalfWaves(0f, 48f, halfWavePx = 12f, controlOffsetPx = 3f)

        assertEquals(4, waves.size)
        assertEquals(listOf(-3f, 3f, -3f, 3f), waves.map { it.controlOffsetY })
    }

    @Test
    fun `degenerate width yields no half waves instead of throwing`() {
        assertTrue(waveHalfWaves(10f, 10f, 12f, 3f).isEmpty())
        assertTrue(waveHalfWaves(10f, 4f, 12f, 3f).isEmpty())
    }

    @Test
    fun `dash periods cover the whole width after scaling`() {
        val (periods, on, off) = scaledDashSegments(100f, 8f, 5f)

        assertEquals(8, periods)
        // 余量摊匀后总长正好等于可用宽度，段尾不会被截成碎段
        assertEquals(100f, periods * on + (periods - 1) * off, 1e-3f)
    }

    @Test
    fun `zero dash and wave lengths cannot divide by zero`() {
        val (periods, on, off) = scaledDashSegments(50f, 0f, 0f)
        assertTrue(periods >= 1)
        assertTrue(on.isFinite() && off.isFinite())
    }

    @Test
    fun `fluorescent band exposes colour and feather only`() {
        val support = underlineControlSupport(7)

        // 色带是填充矩形，不是描边：线宽/偏移/层级对它都没有意义
        assertFalse(support.width)
        assertFalse(support.offset)
        assertFalse(support.layer)
        assertFalse(support.dashPattern)
        assertFalse(support.waveShape)
        assertTrue(support.feather)
    }

    @Test
    fun `every line type shares one fixed corner radius`() {
        // 端点圆角不再是开关：所有线型统一收 READER_UNDERLINE_CORNER_DP 的小圆角。
        assertTrue(READER_UNDERLINE_CORNER_DP > 0f)

        // 半径不超过半个线宽：线芯就那么粗，再大只是把端点变成半圆
        assertEquals(1f, underlineCornerRadiusPx(8f, 3f), 1e-6f)
        assertEquals(2f, underlineCornerRadiusPx(4f, 3f), 1e-6f)
        // 亚像素线宽抬到 1px 后取半宽
        assertEquals(0.5f, underlineCornerRadiusPx(0.2f, 3f), 1e-6f)
        // 半径为 0 表示不收边
        assertEquals(0f, underlineCornerRadiusPx(8f, 0f), 0f)
    }

    @Test
    fun `custom svg exposes no feather`() {
        val support = underlineControlSupport(5)

        assertTrue(support.width)
        assertFalse("SVG 的形状由用户路径决定，羽化不适用", support.feather)
    }

    @Test
    fun `double line and strike expose no feather`() {
        // 双实线两条线各自成段、删除线固定在行高比例处，羽化都只会把两线之间的
        // 间隙或删除线糊到字上
        listOf(4, 6).forEach { mode ->
            val support = underlineControlSupport(mode)
            assertTrue("mode $mode 应该有线宽", support.width)
            assertFalse("mode $mode 不该有羽化", support.feather)
        }
    }

    @Test
    fun `wave shape controls appear for wave only`() {
        assertTrue(underlineControlSupport(3).waveShape)
        listOf(0, 1, 2, 4, 5, 6, 7).forEach { mode ->
            assertFalse("mode $mode 不该有波浪形状参数", underlineControlSupport(mode).waveShape)
        }
    }


    @Test
    fun `feather spread is the core half width plus the radius`() {
        assertEquals(2f + 5f, featherSpreadPx(4f, 5f), 1e-4f)
        // 亚像素线宽抬到 1px 后再折半
        assertEquals(0.5f, featherSpreadPx(0.2f, 0f), 1e-4f)
    }

    @Test
    fun `double line second offset is gap plus core width`() {
        // 回归背景：正文绘制分支丢失后双实线塌成单线、选区预览却还是双线。
        // 三处（正文/选区预览/笔记列表）共用这一个偏移口径：净间隙 + 线芯宽度。
        assertEquals(3f + 4f, doubleLineSecondOffsetPx(3f, 4f), 1e-6f)
        // 负间隙钳到 0：第二条线贴着第一条，不画到第一条上方
        assertEquals(4f, doubleLineSecondOffsetPx(-1f, 4f), 1e-6f)
    }

    @Test
    fun `feather profile is monotonically decreasing from the centre`() {
        val weights = READER_FEATHER_PROFILE_WEIGHTS
        assertEquals(1f, weights.first(), 1e-6f)
        weights.zipWithNext().forEach { (a, b) -> assertTrue("应递减", a > b) }
        // 边缘保留一点 alpha，不能掉到 0：端点全透明会让边界看着被切掉
        assertTrue(weights.last() > 0f)
    }

    @Test
    fun `vertical feather stops are symmetric around the stroke core`() {
        // 回归背景：旧实现把单调剖面直接铺在描边带自上而下的方向上，上缘满浓生硬、
        // 下缘几乎透明 —— 正文「只有下方模糊、波浪下半部糊成平带」正是它。
        // 垂直剖面必须沿线芯（0.5）上下对称。
        val (positions, alphas) = featherVerticalStops()
        val n = READER_FEATHER_PROFILE_POSITIONS.size

        assertEquals(n * 2 - 1, positions.size)
        assertEquals(positions.size, alphas.size)
        // 位置覆盖整条描边带：0=上缘、0.5=线芯、1=下缘
        assertEquals(0f, positions.first(), 1e-6f)
        assertEquals(1f, positions.last(), 1e-6f)
        assertEquals(0.5f, positions[n - 1], 1e-6f)

        // 与中点等距的色标位置和权重一致
        for (i in positions.indices) {
            val mirror = positions.size - 1 - i
            assertEquals("位置应关于 0.5 对称", 1f - positions[i], positions[mirror], 1e-5f)
            assertEquals("权重应上下对称", alphas[i], alphas[mirror], 1e-6f)
        }

        // 线芯最浓、两缘最弱（与半剖面同表），且两段各自单调
        assertEquals(1f, alphas[n - 1], 1e-6f)
        assertEquals(READER_FEATHER_PROFILE_WEIGHTS.last(), alphas.first(), 1e-6f)
        for (i in 0 until n - 1) assertTrue("向上缘应变淡", alphas[i] < alphas[i + 1])
        for (i in n - 1 until alphas.size - 1) assertTrue("向下缘应变淡", alphas[i] > alphas[i + 1])
    }

    @Test
    fun `edge fade ratio is capped so short segments keep a solid core`() {
        assertEquals(0f, edgeFadeRatio(0f, 200f), 0f)
        // 长段：名义值就是羽化半径占比
        assertEquals(0.05f, edgeFadeRatio(5f, 100f), 1e-4f)
        // 短段：封顶 0.25，两端淡出合计不超过段宽一半
        assertEquals(0.25f, edgeFadeRatio(30f, 45f), 1e-4f)
    }

    @Test
    fun `band edge stops keep a solid plateau in the middle`() {
        val (positions, alphas) = featherEdgeStops(30f, 40f)
        assertEquals(positions.size, alphas.size)
        // 上升沿封顶到 0.5：再宽中间平台就没了，模糊调大反而看不出差别
        assertEquals(0f, positions.first(), 1e-6f)
        assertEquals(1f, positions.last(), 1e-6f)
        assertEquals(0.5f, (positions.indices).map { positions[it] }.sorted()[positions.size / 2], 1e-4f)
        // 两端最弱、中间最强（权重表倒着取）
        assertTrue(alphas.first() < alphas.max())
        assertEquals(1f, alphas.max(), 1e-6f)
    }

    @Test
    fun `band edge stops follow the radius instead of collapsing`() {
        // 旧实现按 bandHeight/2 封顶内缩量，模糊大到一定程度色带中间被吃空，
        // 于是「模糊 1」和「模糊 10」画出来一样。现在上升沿随半径单调变宽。
        val small = featherEdgeStops(3f, 40f).first[READER_FEATHER_PROFILE_POSITIONS.size - 1]
        val large = featherEdgeStops(15f, 40f).first[READER_FEATHER_PROFILE_POSITIONS.size - 1]
        assertTrue("上升沿应随半径变宽：$small vs $large", large > small)
    }

    @Test
    fun `band edge colours keep the source alpha`() {
        val alphas = floatArrayOf(0f, 0.5f, 1f)
        val colors = featherEdgeColors(alphas, 0x80FF0000.toInt())
        assertEquals(0x00, colors[0] ushr 24 and 0xFF)
        assertEquals(0x40, colors[1] ushr 24 and 0xFF)
        assertEquals(0x80, colors[2] ushr 24 and 0xFF)
        // RGB 全程保持基色
        assertTrue(colors.all { (it and 0x00FFFFFF) == 0x00FF0000 })
    }

    fun `strike ignores vertical offset because it sits at a fixed height ratio`() {
        val support = underlineControlSupport(6)

        assertTrue(support.width)
        assertFalse(support.offset)
        assertFalse(support.dashPattern)
    }

    @Test
    fun `unknown and disabled modes fall back to no geometry`() {
        listOf(0, -1, 8, 99).forEach { mode ->
            val support = underlineControlSupport(mode)
            assertFalse(
                "mode $mode 不该暴露任何参数",
                support.width || support.offset || support.feather ||
                        support.dashPattern || support.waveShape,
            )
        }
    }

    @Test
    fun `only dashed exposes the dash pattern`() {
        assertTrue(underlineControlSupport(2).dashPattern)
        listOf(0, 1, 3, 4, 5, 6, 7).forEach { mode ->
            assertFalse("mode $mode 不该有虚线段长", underlineControlSupport(mode).dashPattern)
        }
    }
}
