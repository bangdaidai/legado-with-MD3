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
     * 羽化 + 端点圆角开启：圆弧尖端落在段边界上，且**边界处仍有颜色**。
     *
     * 这是"圆头 vs 尖头"的判据。旧实现把渐隐窗口整个放在段内（`[left, left+2F]`，
     * 中心偏到 `left+F`），尖端恰好落在 alpha=0 处——圆弧画得再圆，从全透明长
     * 出来的东西都是尖的。窗口中心对齐段边界后，端点带着约一半 alpha。
     *
     * 同时锁住"不往外多接一段"：圆弧按当趟宽度内缩，总长恒等于段宽，段外无覆盖。
     */
    @Test
    fun `feathered round cap keeps its tip inside the segment with visible alpha`() {
        val bitmap = Bitmap.createBitmap(80, 24, Bitmap.Config.ARGB_8888)
        val bounds = ReaderRect(20f, 0f, 60f, 16f)

        ReaderUnderlineDrawCommand(
            bounds,
            ReaderUnderline(1, 0xff000000.toInt(), 1f, 0f, roundCap = true, featherPx = 5f),
        ).draw(Canvas(bitmap))

        assertTrue("圆头内缩，段外不该有覆盖", alpha(bitmap, 18, BASELINE_Y) == 0)
        assertTrue("圆头内缩，段外不该有覆盖", alpha(bitmap, 61, BASELINE_Y) == 0)
        val edge = alpha(bitmap, 20, BASELINE_Y)
        assertTrue("段边界处不是全透明才是圆头，实际 $edge", edge > 0)
        assertTrue("端部仍应淡于中部：$edge", edge < alpha(bitmap, 40, BASELINE_Y))
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
