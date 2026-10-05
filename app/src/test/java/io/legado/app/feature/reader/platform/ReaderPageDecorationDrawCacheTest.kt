package io.legado.app.feature.reader.platform

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.legado.app.feature.reader.core.model.ReaderElement
import io.legado.app.feature.reader.core.model.ReaderPage
import io.legado.app.feature.reader.core.model.ReaderPageId
import io.legado.app.feature.reader.core.model.ReaderRect
import io.legado.app.feature.reader.core.model.ReaderTextStyle
import io.legado.app.feature.reader.core.model.ReaderUnderline
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderPageDecorationDrawCacheTest {

    @After fun clearSvgCache() = ReaderSvgPathCache.clear()

    @Test fun separatesContentAndOverlayRulesAroundMergedStyledUnderlines() {
        val underline = ReaderUnderline(1, 0xff112233.toInt(), 1f, 2f)
        val style = ReaderTextStyle(0xff000000.toInt(), 16f, underline = underline)
        val page = page(
            ReaderElement.Text(ReaderRect(0f, 0f, 5f, 10f), 8f, "甲", style, false, false, chapterPosition = 0),
            ReaderElement.Text(ReaderRect(5f, 0f, 10f, 10f), 8f, "乙", style, false, false, chapterPosition = 1),
            ReaderElement.Rule(ReaderRect(0f, 4f, 10f, 4f), 0xff000000.toInt(), 1f, false),
            ReaderElement.Rule(
                ReaderRect(0f, 12f, 10f, 12f),
                0xff000000.toInt(),
                1f,
                false,
                overlayStyledUnderline = true,
            ),
        )

        val cache = ReaderPageDecorationDrawCache.create(page)

        assertEquals(1, cache.contentRules.size)
        assertEquals(1, cache.styledUnderlines.size)
        assertEquals(1, cache.overlayRules.size)
    }

    @Test fun svgParserCachesSuccessfulPathsAndIgnoresBlankData() {
        val data = "M0 50 L100 50"
        val first = ReaderSvgPathCache.parse(data)

        assertSame(first, ReaderSvgPathCache.parse(data))
        assertNull(ReaderSvgPathCache.parse("  "))

        ReaderSvgPathCache.clear()
        assertNotSame(first, ReaderSvgPathCache.parse(data))
    }

    @Test
    fun halfHighlightIsSeparatedSoItCanBeDrawnBehindText() {
        val style = ReaderTextStyle(
            colorArgb = 0xff000000.toInt(),
            fontSizePx = 16f,
            underline = ReaderUnderline(7, 0x66ffd54f, 1f, 0f),
        )
        val cache = ReaderPageDecorationDrawCache.create(
            page(
                ReaderElement.Text(
                    ReaderRect(0f, 0f, 10f, 10f),
                    8f,
                    "甲",
                    style,
                    false,
                    false,
                    chapterPosition = 0
                )
            )
        )

        assertEquals(1, cache.halfHighlights.size)
        assertEquals(0, cache.styledUnderlines.size)
    }

    @Test(timeout = 1_000)
    fun `fluorescent band survives extreme feather without stalling`() {
        val canvas = Canvas(Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888))
        val bounds = ReaderRect(2f, 0f, 38f, 16f)

        // 圆角已固定，无开关；羽化半径远超色带高度时向内收缩不能把矩形收成负数
        listOf(0f, 1f, 40f).forEach { featherPx ->
            ReaderHalfHighlightDrawCommand(
                bounds,
                ReaderUnderline(7, 0x88ffd54f, 1f, 0f, featherPx = featherPx),
            ).draw(canvas)
        }
    }

    @Test(timeout = 1_000) fun zeroDashAndWaveLengthsCannotStallDrawing() {
        val canvas = Canvas(Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888))
        val bounds = ReaderRect(0f, 0f, 10f, 10f)

        ReaderUnderlineDrawCommand(
            bounds,
            ReaderUnderline(2, 0xff000000.toInt(), 1f, 0f, dashOnPx = 0f, dashOffPx = 0f),
        ).draw(canvas)
        ReaderUnderlineDrawCommand(
            bounds,
            ReaderUnderline(3, 0xff000000.toInt(), 1f, 0f, waveHalfWavePx = 0f),
        ).draw(canvas)
        // 零宽段 + 羽化：LinearGradient 的零长度坐标轴会抛异常，必须走退化点路径
        ReaderUnderlineDrawCommand(
            ReaderRect(5f, 0f, 5f, 10f),
            ReaderUnderline(1, 0xff000000.toInt(), 1f, 0f, featherPx = 5f),
        ).draw(canvas)
    }

    /**
     * 羽化是**各向同性**的：上下靠逐趟加粗、左右靠逐趟内缩，两边一起糊。
     *
     * 这里锁三件事：段外没有覆盖（总长不超过段宽）、端部有颜色而不是空的
     * （外圈低 alpha 趟齐边铺满）、越靠中段越浓。
     */
    @Test(timeout = 1_000)
    fun `feathered stroke softens on all four sides`() {
        val bitmap = Bitmap.createBitmap(80, 24, Bitmap.Config.ARGB_8888)
        val bounds = ReaderRect(20f, 0f, 60f, 16f)

        ReaderUnderlineDrawCommand(
            bounds,
            ReaderUnderline(1, 0xff000000.toInt(), 1f, 0f, featherPx = 5f),
        ).draw(Canvas(bitmap))

        // 端部柔边宽度 = min(羽化半径, 段宽 × 0.25) = 5px，笔形绝不越出段界
        assertTrue("段外左侧不该有覆盖", alpha(bitmap, 16, BASELINE_Y) == 0)
        assertTrue("段外右侧不该有覆盖", alpha(bitmap, 63, BASELINE_Y) == 0)
        val edge = alpha(bitmap, 21, BASELINE_Y)
        assertTrue("端点应有颜色而不是空的，实际 $edge", edge > 0)
        assertTrue("越靠中段越浓：$edge", edge < alpha(bitmap, 40, BASELINE_Y))

        // 上下也必须柔：线芯那一行之外仍有低 alpha 的外圈覆盖，
        // 若端部只降 alpha 不动几何，这里会是 0（上下边缘成了刀锋）
        assertTrue("上下柔边应覆盖到 ±5px", alpha(bitmap, 40, BASELINE_Y - 4) > 0)
        assertTrue("上下柔边应覆盖到 ±5px", alpha(bitmap, 40, BASELINE_Y + 4) > 0)
    }

    /**
     * 四个角不能是「实尖角」。
     *
     * 每趟内部 alpha 是均匀的，只靠逐趟内缩的话角落那个点只有最外那一趟够宽能覆盖，
     * 角上的 alpha 就恒等于最外趟的值，而同一高度的边缘中部已被 7 趟叠到接近饱和 ——
     * 对比之下角上那块就是实的。alpha 必须在水平方向也连续衰减。
     */
    @Test(timeout = 1_000)
    fun `feathered corners fade in both directions`() {
        val bitmap = Bitmap.createBitmap(80, 24, Bitmap.Config.ARGB_8888)

        ReaderUnderlineDrawCommand(
            ReaderRect(20f, 0f, 60f, 16f),
            ReaderUnderline(1, 0xff000000.toInt(), 1f, 0f, featherPx = 5f),
        ).draw(Canvas(bitmap))

        // 上边缘距中心 4px 处：中部（x=40）被多趟覆盖，靠近端点（x=21）只有外圈几趟
        val edgeMiddle = alpha(bitmap, 40, BASELINE_Y - 4)
        val edgeNearEnd = alpha(bitmap, 21, BASELINE_Y - 4)
        assertTrue("上边缘中部应可见：$edgeMiddle", edgeMiddle > 0)
        assertTrue(
            "同一高度上越靠端点越淡：$edgeNearEnd vs $edgeMiddle",
            edgeNearEnd < edgeMiddle,
        )
    }

    /**
     * 短高亮段（1~3 个字）撑不住羽化半径时，两端柔边按段宽的 1/4 封顶，
     * 中段保得住实心，不会被吃成两端尖的纺锤形。
     */
    @Test
    fun `short segment keeps a solid core`() {
        val bitmap = Bitmap.createBitmap(80, 24, Bitmap.Config.ARGB_8888)

        ReaderUnderlineDrawCommand(
            ReaderRect(30f, 0f, 50f, 16f),
            ReaderUnderline(1, 0xff000000.toInt(), 1f, 0f, featherPx = 5f),
        ).draw(Canvas(bitmap))

        val centre = alpha(bitmap, 40, BASELINE_Y)
        assertTrue("中段应接近不透明，实际 $centre", centre > 200)
    }

    /**
     * 羽化 + 周期笔形：虚线与波浪画得出来，且全程只按一个内缩量画形状。
     *
     * 逐趟变内缩量会让 `waveHalfWaves` / `scaledDashSegments` 每趟按新段宽重新
     * 均摊一次，多趟叠起来是一团波长/周期各不相同的交错重影。
     */
    @Test(timeout = 1_000)
    fun `feathered periodic strokes draw with one fixed inset`() {
        val bounds = ReaderRect(20f, 0f, 60f, 16f)
        listOf(2, 3).forEach { mode ->
            val bitmap = Bitmap.createBitmap(80, 24, Bitmap.Config.ARGB_8888)
            ReaderUnderlineDrawCommand(
                bounds,
                ReaderUnderline(mode, 0xff000000.toInt(), 1f, 0f, featherPx = 5f),
            ).draw(Canvas(bitmap))
            // 固定内缩 = 线芯半宽 0.5，段内仍有 39px 可画
            assertTrue("mode $mode 中段应有像素", alpha(bitmap, 40, BASELINE_Y) > 0)
        }
    }

    /**
     * 统一小圆角由羽化的**线芯那一趟**承担，圆弧半径锁在半个线宽上，
     * 不能跟着羽化加粗涨出去。
     *
     * round cap 的半径恒等于 strokeWidth/2。若让加粗到 `widthPx + 2 × featherPx`
     * 的外圈趟也带圆角，半径就是半个羽化半径那么远，端头看着像在段外多接一段。
     */
    @Test(timeout = 1_000)
    fun `feathered stroke keeps its corner inside the segment`() {
        val bitmap = Bitmap.createBitmap(80, 24, Bitmap.Config.ARGB_8888)

        ReaderUnderlineDrawCommand(
            ReaderRect(20f, 0f, 60f, 16f),
            ReaderUnderline(1, 0xff000000.toInt(), 1f, 0f, featherPx = 5f),
        ).draw(Canvas(bitmap))

        // 统一圆角 1dp；Robolectric 下 density 为 1，故 1px。半径 ≤ 半个线宽 = 0.5px。
        assertTrue("段外左侧不该有覆盖", alpha(bitmap, 16, BASELINE_Y) == 0)
        assertTrue("段外右侧不该有覆盖", alpha(bitmap, 63, BASELINE_Y) == 0)
    }

    private fun alpha(bitmap: Bitmap, x: Int, y: Int): Int =
        Color.alpha(bitmap.getPixel(x, y))

    /** 取样基线 = `bounds.bottom + offsetPx`，与绘制侧同一条公式。 */
    private companion object {
        const val BASELINE_Y = 16
    }

    private fun page(vararg elements: ReaderElement) = ReaderPage(
        id = ReaderPageId(0, 0),
        chapterTitle = "",
        text = "甲乙",
        widthPx = 20,
        heightPx = 20,
        contentTopPx = 0f,
        contentBottomPx = 20f,
        elements = elements.toList(),
        revision = 1L,
    )
}
