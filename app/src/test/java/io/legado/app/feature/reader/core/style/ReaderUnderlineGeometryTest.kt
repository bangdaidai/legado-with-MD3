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
    fun `feather pass colour is quantised to eight bits`() {
        // 预览此前用 float alpha、正文用 8bit 截断，羽化是层层叠加的，
        // 每趟差一个分量累积 7~24 趟后就是肉眼可辨的深浅差
        val opaque = 0xFF3366CC.toInt()
        assertEquals(opaque, featherPassArgb(opaque, 0f))
        assertEquals(0xFF3366CC.toInt(), featherPassArgb(opaque, 0f))

        // d=1 远离中心，alpha 应被压到很低
        val faint = featherPassArgb(opaque, 1f)
        assertTrue("外圈应明显变淡", (faint ushr 24) and 0xFF < 80)

        // 结果必须落在 8bit 范围内，不能出现负数或溢出
        (0..10).forEach { i ->
            val d = i / 10f
            listOf(0x00FFFFFF, 0xFFFFFFFF.toInt(), 0x01020304).forEach { c ->
                val argb = featherPassArgb(c, d)
                assertTrue("alpha 越界: ${argb ushr 24}", (argb ushr 24) in 0..255)
                assertEquals("rgb 不该被改动", c and 0x00FFFFFF, argb and 0x00FFFFFF)
            }
        }
    }

    @Test
    fun `feather pass colour keeps the source alpha`() {
        // 半透明源色：羽化只是衰减 alpha，不会把它抬回不透明
        val translucent = 0x803366CC
        val core = featherPassArgb(translucent, 0f)

        assertEquals(0x80, (core ushr 24) and 0xFF)
        assertEquals(0x003366CC, core and 0x00FFFFFF)
    }

    @Test
    fun `band feather collapses to a single opaque pass without radius`() {
        val passes = featherBandPasses(0f)

        assertEquals(1, passes.size)
        assertEquals(0f, passes.single().distance, 0f)
        // 无羽化时不内缩，色带保持原尺寸
        assertEquals(0f, passes.single().insetFactor, 0f)
        assertEquals(0xFFFFFFFF.toInt(), passes.single().argb(0xFFFFFFFF.toInt()))
    }

    @Test
    fun `band feather insets opposite to the draw order`() {
        val passes = featherBandPasses(2f)
        val color = 0xFF3366CC.toInt()

        assertEquals(featherPassCount(2f) + 1, passes.size)
        // 与描边羽化的 `for (i in passes downTo 0)` 同一顺序：先最虚，最后最实
        assertEquals(1f, passes.first().distance, 1e-6f)
        assertEquals(0f, passes.last().distance, 0f)
        // 内缩必须与叠加顺序反向：最虚那趟铺满色带，最实那趟收成核心。
        // 同向会自我抵消——最后画的那趟满不透明又铺满整个色带，把前面全盖住。
        assertEquals(0f, passes.first().insetFactor, 1e-6f)
        assertEquals(1f, passes.last().insetFactor, 1e-6f)
        // alpha 仍是最虚 → 最实
        assertTrue(passes.first().argb(color) < passes.last().argb(color))
    }

    @Test
    fun `band feather inset and alpha move in opposite directions`() {
        val passes = featherBandPasses(2f)

        passes.zipWithNext().forEach { (outer, inner) ->
            assertTrue(
                "越靠内应该内缩越多",
                outer.insetFactor < inner.insetFactor,
            )
            assertTrue(
                "越靠内应该越不透明",
                outer.argb(0xFF3366CC.toInt()) < inner.argb(0xFF3366CC.toInt()),
            )
        }
    }

    @Test
    fun `band feather inset is capped by the band short side`() {
        // 半行高的色带配大羽化：内缩不能吃掉整条色带，否则是「变窄」而非柔化
        assertEquals(0f, bandFeatherMaxInsetPx(0f, 36f, 200f), 0f)
        assertEquals(9f, bandFeatherMaxInsetPx(9f, 36f, 200f), 1e-4f)
        // 超过短边一半时封顶
        assertEquals(18f, bandFeatherMaxInsetPx(40f, 36f, 200f), 1e-4f)
    }

    @Test
    fun `band feather inset never eats more than half the band width`() {
        // 内缩是四边一起收的，短边约束只看垂直方向。1 个字的高亮段宽约 45px、
        // 羽化 15px，不按段宽封顶的话两端各内缩 15px、实心区只剩 15px，
        // 整条色带看着像「两端内陷了一截」。
        assertEquals(11.25f, bandFeatherMaxInsetPx(15f, 36f, 45f), 1e-4f)
        // 超大羽化同样被段宽卡住（20 / 4 = 5）
        assertEquals(5f, bandFeatherMaxInsetPx(40f, 36f, 20f), 1e-4f)
        // 窄命中段：原先按短边取 6px，两端合计 12px 会把 12px 宽的段吃光
        assertEquals(3f, bandFeatherMaxInsetPx(40f, 36f, 12f), 1e-4f)
    }

    @Test
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
