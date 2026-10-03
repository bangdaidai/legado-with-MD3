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
    fun `fluorescent band exposes colour plus round cap and feather only`() {
        val support = underlineControlSupport(7)

        // 色带是填充矩形：线宽/偏移/层级对它没有意义
        assertFalse(support.width)
        assertFalse(support.offset)
        assertFalse(support.layer)
        assertFalse(support.dashPattern)
        assertFalse(support.waveShape)
        // 但色带有边缘，圆头和羽化成立
        assertTrue(support.roundCap)
        assertTrue(support.feather)
    }

    @Test
    fun `custom svg rejects feather because its ends are user drawn`() {
        val support = underlineControlSupport(5)

        assertTrue(support.width)
        assertTrue(support.roundCap)
        assertFalse(support.feather)
    }

    @Test
    fun `wave shape controls appear for wave only`() {
        assertTrue(underlineControlSupport(3).waveShape)
        listOf(0, 1, 2, 4, 5, 6, 7).forEach { mode ->
            assertFalse("mode $mode 不该有波浪形状参数", underlineControlSupport(mode).waveShape)
        }
    }

    @Test
    fun `feather pass count scales with radius and stays bounded`() {
        assertEquals(READER_FEATHER_MIN_PASSES, featherPassCount(0.1f))
        assertEquals(READER_FEATHER_MAX_PASSES, featherPassCount(100f))
        assertTrue(featherPassCount(2f) in READER_FEATHER_MIN_PASSES..READER_FEATHER_MAX_PASSES)
    }

    @Test
    fun `feather gaussian falls off from centre`() {
        assertEquals(1f, featherGaussian(0f), 1e-6f)
        val outer = featherGaussian(1f)
        val middle = featherGaussian(0.5f)
        assertTrue("越靠外越透明", outer < middle)
    }

    @Test
    fun `band feather collapses to a single opaque pass without radius`() {
        val passes = featherBandPasses(0f)

        assertEquals(1, passes.size)
        assertEquals(1f, passes.single().alphaScale, 0f)
    }

    @Test
    fun `band feather stacks alpha from opaque to transparent`() {
        val passes = featherBandPasses(2f)

        assertEquals(featherPassCount(2f) + 1, passes.size)
        // 第一趟最实（alpha = 1），往后逐趟变淡，靠叠加模拟模糊
        assertEquals(1f, passes.first().alphaScale, 1e-6f)
        assertTrue("alpha 应逐趟递减", passes.first().alphaScale > passes.last().alphaScale)
        assertTrue(passes.all { it.alphaScale in 0f..1f })
    }

    @Test
    fun `strike ignores vertical offset because it sits at a fixed height ratio`() {
        val support = underlineControlSupport(6)

        assertTrue(support.width)
        assertFalse(support.offset)
        assertTrue(support.roundCap)
        assertTrue(support.feather)
        assertFalse(support.dashPattern)
    }

    @Test
    fun `unknown and disabled modes fall back to no geometry`() {
        listOf(0, -1, 8, 99).forEach { mode ->
            val support = underlineControlSupport(mode)
            assertFalse(
                "mode $mode 不该暴露任何参数",
                support.width || support.offset || support.roundCap || support.feather ||
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