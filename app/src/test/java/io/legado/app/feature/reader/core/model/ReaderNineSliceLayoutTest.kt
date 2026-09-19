package io.legado.app.feature.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ReaderNineSliceLayout 已退出正文渲染路径（九宫格外扩改用
 * io.legado.app.help.highlight.NinePatchDrawHelper，与规则编辑预览同一套公式）。
 * 本文件仅覆盖仍在源码中保留的切图数学；待确认删除死代码后整个文件移除。
 */
class ReaderNineSliceLayoutTest {
    @Test
    fun centerStaysOnTextWhileEightFrameCellsUseExpandedBounds() {
        val image = ReaderTextBackgroundImage(
            "frame.png", 3, 1f,
            ninePatchLeft = 0.2f,
            ninePatchRight = 0.3f,
            ninePatchTop = 0.1f,
            ninePatchBottom = 0.2f,
        )
        val content = ReaderRect(10f, 20f, 40f, 50f)
        val frame = ReaderRect(0f, 16f, 55f, 58f)

        val cells = ReaderNineSliceLayout.cells(50, 40, content, frame, image)

        assertEquals(9, cells.size)
        assertEquals(ReaderNineSliceCell(ReaderIntRect(10, 4, 35, 32), content), cells[4])
        assertEquals(frame.left, cells.first().destination.left, 0f)
        assertEquals(frame.top, cells.first().destination.top, 0f)
        assertEquals(frame.right, cells.last().destination.right, 0f)
        assertEquals(frame.bottom, cells.last().destination.bottom, 0f)
    }

    @Test
    fun rawNinePatchGuideBorderIsExcludedFromEverySourceCell() {
        val image = ReaderTextBackgroundImage(
            "frame.9.png", 3, 1f,
            ninePatchLeft = 0.2f,
            ninePatchRight = 0.3f,
            ninePatchTop = 0.1f,
            ninePatchBottom = 0.2f,
        )

        val cells = ReaderNineSliceLayout.cells(
            bitmapWidth = 52,
            bitmapHeight = 42,
            content = ReaderRect(10f, 4f, 35f, 32f),
            frame = ReaderRect(0f, 0f, 50f, 40f),
            image = image,
        )

        assertEquals(ReaderIntRect(1, 1, 11, 5), cells.first().source)
        assertEquals(ReaderIntRect(36, 33, 51, 41), cells.last().source)
    }

    @Test
    fun fractionalMarginsRoundBackToTheirOriginalPixelBoundaries() {
        val image = ReaderTextBackgroundImage(
            "frame.9.png", 3, 1f,
            ninePatchLeft = 7f / 31f,
            ninePatchRight = 9f / 31f,
            ninePatchTop = 5f / 29f,
            ninePatchBottom = 8f / 29f,
        )

        val cells = ReaderNineSliceLayout.cells(
            bitmapWidth = 33,
            bitmapHeight = 31,
            content = ReaderRect(7f, 5f, 22f, 21f),
            frame = ReaderRect(0f, 0f, 31f, 29f),
            image = image,
        )

        assertEquals(ReaderIntRect(1, 1, 8, 6), cells.first().source)
        assertEquals(ReaderIntRect(23, 22, 32, 30), cells.last().source)
    }
}
