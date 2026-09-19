package io.legado.app.feature.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

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
    fun withoutALineGapOnlyTheCenterAndTheSideCellsSurvive() {
        // 无行距时上下边厚度为 0（对照旧 View TextLine.drawNineSliceFrames 里
        // overflowScale = 0），上下两行连同四角一起消失，只剩中心 + 左右两条边。
        val image = ReaderTextBackgroundImage(
            "frame.png", 3, 1f,
            contentInsetLeftPx = 7f,
            contentInsetRightPx = 5f,
        )
        val content = ReaderRect(10f, 20f, 40f, 50f)
        val frame = ReaderRect(3f, 20f, 45f, 50f)

        val cells = ReaderNineSliceLayout.cells(10, 10, content, frame, image)

        assertEquals(3, cells.size)
        // 左右边保持原图厚度，且落在文字框外侧（不压在字上）。
        assertEquals(ReaderRect(3f, 20f, 10f, 50f), cells.first().destination)
        assertEquals(ReaderRect(40f, 20f, 45f, 50f), cells.last().destination)
        assertEquals(content, cells[1].destination)
        assertEquals(ReaderIntRect(1, 1, 9, 9), cells[1].source)
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
    fun fixedCornersKeepOneUniformConfiguredScale() {
        val image = ReaderTextBackgroundImage(
            "frame.png", 3, 0.5f,
            ninePatchLeft = 0.2f,
            ninePatchRight = 0.3f,
            ninePatchTop = 0.1f,
            ninePatchBottom = 0.2f,
        ).withBitmapSize(50, 40)
        val content = ReaderRect(5f, 2f, 30f, 30f)
        val frame = ReaderRect(
            content.left - image.contentInsetLeftPx,
            content.top - image.contentInsetTopPx,
            content.right + image.contentInsetRightPx,
            content.bottom + image.contentInsetBottomPx,
        )

        val topLeft = ReaderNineSliceLayout.cells(50, 40, content, frame, image).first()

        assertEquals(topLeft.source.right - topLeft.source.left, 10)
        assertEquals(topLeft.source.bottom - topLeft.source.top, 4)
        assertEquals(5f, topLeft.destination.width, 0f)
        assertEquals(2f, topLeft.destination.height, 0f)
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

    @Test
    fun anchoredToLineHeightScalesCornersFromMiddleBandLikePreview() {
        val image = ReaderTextBackgroundImage(
            "frame.png", 3, 1f,
            ninePatchLeft = 0.2f,
            ninePatchRight = 0.2f,
            ninePatchTop = 0.2f,
            ninePatchBottom = 0.1f,
            paddingLeftPx = 2f,
        ).withBitmapSize(100, 50)

        // 中带源高 (1-0.2-0.1)*50=35，锚定行盒高 40 → 全图等比 scale=40/35，
        // 四角尺寸随行高变化（与 NinePatchDrawHelper.layout 同一口径），padding 不变
        val anchored = image.anchoredToLineHeight(40f)
        assertEquals(50f * 0.2f * 40f / 35f, anchored.contentInsetTopPx, 0.1f)
        assertEquals(50f * 0.1f * 40f / 35f, anchored.contentInsetBottomPx, 0.1f)
        assertEquals(100f * 0.2f * 40f / 35f + 2f, anchored.contentInsetLeftPx, 0.1f)

        // 无源图尺寸（测试直接构造 inset）时锚定不生效，保持既有行为
        val noSource = ReaderTextBackgroundImage("frame.png", 3, 1f, contentInsetTopPx = 9f)
        assertEquals(9f, noSource.anchoredToLineHeight(40f).contentInsetTopPx, 0f)
    }
}
