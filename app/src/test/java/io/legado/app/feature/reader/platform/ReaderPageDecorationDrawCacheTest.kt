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
    fun `fluorescent band survives round cap and extreme feather without stalling`() {
        val canvas = Canvas(Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888))
        val bounds = ReaderRect(2f, 0f, 38f, 16f)

        // 圆头 + 羽化：色带要真画得出来；羽化半径远超色带高度时向内收缩不能把矩形收成负数
        listOf(false, true).forEach { roundCap ->
            listOf(0f, 1f, 40f).forEach { featherPx ->
                ReaderHalfHighlightDrawCommand(
                    bounds,
                    ReaderUnderline(7, 0x88ffd54f, 1f, 0f, roundCap = roundCap, featherPx = featherPx),
                ).draw(canvas)
            }
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
            ReaderUnderline(1, 0xff000000.toInt(), 1f, 0f, roundCap = true, featherPx = 5f),
        ).draw(canvas)
    }

    /**
     * 羽化 + 端点圆角关闭：端头必须是齐边的淡出，而不是被圆弧收出的尖锥。
     *
     * 旧口径在羽化时强制 ROUND cap，端点被圆弧收窄再乘上 alpha 渐隐，宽度 1px /
     * 羽化 5px 这类比例下看着就是两头尖。关掉圆角后笔形齐平切口，只随水平渐隐淡出。
     */
@Test
    fun `feathered butt cap stays inside the segment and fades at both ends`() {
        val bitmap = Bitmap.createBitmap(80, 24, Bitmap.Config.ARGB_8888)
        val bounds = ReaderRect(20f, 0f, 60f, 16f)

        ReaderUnderlineDrawCommand(
            bounds,
            ReaderUnderline(1, 0xff000000.toInt(), 1f, 0f, roundCap = false, featherPx = 5f),
        ).draw(Canvas(bitmap))

        assertTrue("段外左侧不该有覆盖", alpha(bitmap, 18, BASELINE_Y) == 0)
        assertTrue("段外右侧不该有覆盖", alpha(bitmap, 61, BASELINE_Y) == 0)
        val leftEdge = alpha(bitmap, 20, BASELINE_Y)
        assertTrue("端部应淡于中部：$leftEdge", leftEdge < alpha(bitmap, 40, BASELINE_Y))
    }

    /**
     * 羽化 + 端点圆角：圆弧半径锁在半个线宽上，**不能**跟着羽化加粗涨出去。
     *
     * round cap 的半径恒等于 strokeWidth/2。若让加粗到 `widthPx + 2 × featherPx`
     * 的外圈趟也带圆头，半径就是半个羽化半径那么远，端头看着像在段外多接一段。
     */
    @Test(timeout = 1_000)
    fun `feathered round cap never reaches past half a line width`() {
        val bitmap = Bitmap.createBitmap(80, 24, Bitmap.Config.ARGB_8888)
        val bounds = ReaderRect(20f, 0f, 60f, 16f)

        ReaderUnderlineDrawCommand(
            bounds,
            ReaderUnderline(1, 0xff000000.toInt(), 1f, 0f, roundCap = true, featherPx = 5f),
        ).draw(Canvas(bitmap))

        // 线宽 1px → 圆头最多探出 0.5px；往左 3px 处必然什么都没有
        assertTrue("段外不该有覆盖", alpha(bitmap, 16, BASELINE_Y) == 0)
        assertTrue("段外不该有覆盖", alpha(bitmap, 63, BASELINE_Y) == 0)
    }

    /**
     * 羽化 + 端部柔边：端部必须**同时**变淡和变窄。
     *
     * 只降 alpha、不动几何时，端部那条带子线宽恒为 `widthPx + 2 × featherPx`，
     * 它的上、下边缘是垂直硬线，只有左边缘在渐变——看着就是生硬切口。端部靠逐趟
     * 内缩收窄（与荧光色带 `featherBandPasses` 同口径），上下边缘才跟着柔化。
     *
     * 端点也不能空掉：外圈那几趟齐边、低 alpha，端点因此是「淡但有颜色」，不是尖。
     */
    @Test(timeout = 1_000)
    fun `feathered ends soften in width as well as alpha`() {
        val bitmap = Bitmap.createBitmap(80, 24, Bitmap.Config.ARGB_8888)
        val bounds = ReaderRect(20f, 0f, 60f, 16f)

        ReaderUnderlineDrawCommand(
            bounds,
            ReaderUnderline(1, 0xff000000.toInt(), 1f, 0f, featherPx = 5f),
        ).draw(Canvas(bitmap))

        assertTrue("段外不该有覆盖", alpha(bitmap, 16, BASELINE_Y) == 0)
        assertTrue("端点应有颜色而不是空的", alpha(bitmap, 21, BASELINE_Y) > 0)
        assertTrue(
            "越靠中段越浓",
            alpha(bitmap, 21, BASELINE_Y) < alpha(bitmap, 40, BASELINE_Y),
        )
    }

    /**
     * 短高亮段不能被端部淡出吃成纺锤形。
     *
     * 渐隐长度名义上取 `2 × 羽化半径`，但 1~3 个字的段宽撑不住：段宽 20px、
     * 羽化 5px 时名义渐隐 10px 占掉半段，两端窗口重叠、`edgePos` 被 0.5 截住，
     * 只剩中点最浓 —— 就是"两头尖"。封顶到段宽的 1/4 后中段仍是实心。
     */
    @Test
    fun `short segment keeps a solid core instead of tapering to a spindle`() {
        val bitmap = Bitmap.createBitmap(80, 24, Bitmap.Config.ARGB_8888)

        ReaderUnderlineDrawCommand(
            ReaderRect(30f, 0f, 50f, 16f),
            ReaderUnderline(1, 0xff000000.toInt(), 1f, 0f, roundCap = false, featherPx = 5f),
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
                ReaderUnderline(mode, 0xff000000.toInt(), 1f, 0f, roundCap = true, featherPx = 5f),
            ).draw(Canvas(bitmap))
            // 固定内缩 = 线芯半宽 0.5，段内仍有 39px 可画
            assertTrue("mode $mode 中段应有像素", alpha(bitmap, 40, BASELINE_Y) > 0)
        }
    }

    /**
     * 羽化 + 端点圆角：圆弧半径锁在半个线宽上，**不能**跟着羽化加粗涨出去。
     *
     * round cap 的半径恒等于 strokeWidth/2。若让加粗到 `widthPx + 2 × featherPx`
     * 的外圈趟也带圆头，半径就是半个羽化半径那么远，端头看着像在段外多接一段。
     * 锁成线芯半宽后，圆头只在高亮范围内把直线收成半圆，端点仍在段边界上，
     * 而 alpha 由 shader 独立给，所以端点不是全透明、也不显尖。
     */
    @Test(timeout = 1_000)
    fun `feathered round cap stays half a line width past the segment`() {
        val bitmap = Bitmap.createBitmap(80, 24, Bitmap.Config.ARGB_8888)
        val bounds = ReaderRect(20f, 0f, 60f, 16f)

        ReaderUnderlineDrawCommand(
            bounds,
            ReaderUnderline(1, 0xff000000.toInt(), 1f, 0f, roundCap = true, featherPx = 5f),
        ).draw(Canvas(bitmap))

        // 线宽 1px → 圆头探出 0.5px；往左 3px 处必然什么都没有
        assertTrue("段外不该有覆盖", alpha(bitmap, 16, BASELINE_Y) == 0)
        val edge = alpha(bitmap, 20, BASELINE_Y)
        assertTrue("端点应带有颜色而不是全透明，实际 $edge", edge > 0)
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
