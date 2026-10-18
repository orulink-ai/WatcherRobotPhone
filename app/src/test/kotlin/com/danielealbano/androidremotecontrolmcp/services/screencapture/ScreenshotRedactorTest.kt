package com.danielealbano.androidremotecontrolmcp.services.screencapture

import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ScreenshotRedactor.computeMaskRects")
class ScreenshotRedactorTest {
    private val redactor = ScreenshotRedactor()

    @Test
    fun `scales bounds and expands by padding`() {
        val rects =
            redactor.computeMaskRects(
                bounds = listOf(BoundsData(100, 200, 300, 400)),
                scaleX = 0.5f,
                scaleY = 0.5f,
                bitmapWidth = 540,
                bitmapHeight = 1200,
            )
        assertEquals(1, rects.size)
        val rect = rects[0]
        // 100*0.5-2, 200*0.5-2, 300*0.5+2, 400*0.5+2
        assertEquals(48f, rect.left)
        assertEquals(98f, rect.top)
        assertEquals(152f, rect.right)
        assertEquals(202f, rect.bottom)
    }

    @Test
    fun `clamps negative and overflowing edges to bitmap bounds`() {
        val rects =
            redactor.computeMaskRects(
                bounds = listOf(BoundsData(0, 0, 1000, 10)),
                scaleX = 1f,
                scaleY = 1f,
                bitmapWidth = 500,
                bitmapHeight = 500,
            )
        assertEquals(1, rects.size)
        val rect = rects[0]
        assertEquals(0f, rect.left)
        assertEquals(0f, rect.top)
        assertEquals(500f, rect.right)
        assertEquals(12f, rect.bottom)
    }

    @Test
    fun `drops boxes that collapse to zero area after clamping`() {
        val rects =
            redactor.computeMaskRects(
                bounds = listOf(BoundsData(600, 600, 700, 700)),
                scaleX = 1f,
                scaleY = 1f,
                bitmapWidth = 500,
                bitmapHeight = 500,
            )
        assertTrue(rects.isEmpty())
    }

    @Test
    fun `returns empty list for empty bounds`() {
        val rects =
            redactor.computeMaskRects(
                bounds = emptyList(),
                scaleX = 1f,
                scaleY = 1f,
                bitmapWidth = 500,
                bitmapHeight = 500,
            )
        assertTrue(rects.isEmpty())
    }

    @Test
    fun `produces one rect per in-bounds box`() {
        val rects =
            redactor.computeMaskRects(
                bounds =
                    listOf(
                        BoundsData(10, 10, 100, 100),
                        BoundsData(200, 200, 300, 300),
                    ),
                scaleX = 1f,
                scaleY = 1f,
                bitmapWidth = 500,
                bitmapHeight = 500,
            )
        assertEquals(2, rects.size)
    }
}
