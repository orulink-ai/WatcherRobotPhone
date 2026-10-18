package com.danielealbano.androidremotecontrolmcp.services.control

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEdgeColorMode
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEffectsConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlEdgeRendererTest {
    @Test
    fun lightStaysAtTheEdgesAndLeavesContentTransparent() {
        val image = render(1200L, animate = true)
        try {
            assertEquals(Color.TRANSPARENT, image.getPixel(180, 360))
            assertEquals(Color.TRANSPARENT, image.getPixel(0, 0))
            for ((x, y) in listOf(4 to 360, 355 to 360, 180 to 4, 180 to 715)) {
                val color = image.getPixel(x, y)
                assertTrue("Each edge must contain light", Color.alpha(color) > 0)
                assertTrue("The reference blue is retained", Color.blue(color) > Color.red(color))
            }
        } finally {
            image.recycle()
        }
    }

    @Test
    fun localHighlightsActuallyChangeTheRenderedEdges() {
        val renderer =
            ControlEdgeRenderer(1f).apply {
                updateConfig(ControlEffectsConfig(colorMode = ControlEdgeColorMode.BLUE))
                resize(360, 720)
            }
        val first = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
        val later = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(first), 0L, active = true, animate = true)
        renderer.draw(Canvas(later), 2600L, active = true, animate = true)
        try {
            val bands =
                listOf(
                    intArrayOf(4, 40, 28, 680),
                    intArrayOf(332, 40, 356, 680),
                    intArrayOf(40, 4, 320, 28),
                    intArrayOf(40, 692, 320, 716),
                )
            for (band in bands) {
                var changedPixels = 0
                for (y in band[1] until band[3]) {
                    for (x in band[0] until band[2]) {
                        if (first.getPixel(x, y) != later.getPixel(x, y)) changedPixels++
                    }
                }
                assertTrue(
                    "Each edge must evolve across a visible band rather than one quantized pixel",
                    changedPixels > 100,
                )
            }
        } finally {
            first.recycle()
            later.recycle()
        }
    }

    @Test
    fun resizingTheSameRendererKeepsTheNewContentAreaClear() {
        val renderer = ControlEdgeRenderer(1f)
        renderer.resize(360, 720)
        renderer.resize(720, 360)
        val image = Bitmap.createBitmap(720, 360, Bitmap.Config.ARGB_8888)
        try {
            renderer.draw(Canvas(image), 1200L, active = true, animate = true)
            assertEquals(Color.TRANSPARENT, image.getPixel(360, 180))
            for ((x, y) in listOf(4 to 180, 715 to 180, 360 to 4, 360 to 355)) {
                assertTrue(Color.alpha(image.getPixel(x, y)) > 0)
            }
        } finally {
            image.recycle()
        }
    }

    @Test
    fun disablingAnimationFreezesTheEntireRenderedEffect() {
        val first = render(0L, animate = false)
        val later = render(18000L, animate = false)
        try {
            val a = IntArray(360 * 720)
            val b = IntArray(a.size)
            first.getPixels(a, 0, 360, 0, 0, 360, 720)
            later.getPixels(b, 0, 360, 0, 0, 360, 720)
            assertArrayEquals(a, b)
        } finally {
            first.recycle()
            later.recycle()
        }
    }

    private fun render(
        now: Long,
        animate: Boolean,
    ): Bitmap {
        val image = Bitmap.createBitmap(360, 720, Bitmap.Config.ARGB_8888)
        ControlEdgeRenderer(1f)
            .apply {
                updateConfig(ControlEffectsConfig(colorMode = ControlEdgeColorMode.BLUE))
                resize(image.width, image.height)
            }.draw(Canvas(image), now, active = true, animate)
        return image
    }
}
