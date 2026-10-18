package com.danielealbano.androidremotecontrolmcp.services.control

import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import androidx.test.platform.app.InstrumentationRegistry
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEdgeColorMode
import com.danielealbano.androidremotecontrolmcp.data.model.ControlEffectsConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests actual Android shaders and live View updates, without dispatching input or opening apps. */
class ControlEffectsConfigurationRenderTest {
    @Test
    fun customColorReplacesThePaletteWithoutAnotherResize() {
        val renderer = renderer(ControlEffectsConfig(colorMode = ControlEdgeColorMode.BLUE))
        val blue = render(renderer)
        renderer.updateConfig(
            ControlEffectsConfig(colorMode = ControlEdgeColorMode.CUSTOM, customColorArgb = Color.RED),
        )
        val red = render(renderer)
        try {
            val before = edgeChannelMass(blue)
            val after = edgeChannelMass(red)
            assertTrue("Blue mode must remain blue", before[2] > before[0])
            assertTrue("A custom red must reach existing shader geometry immediately", after[0] > after[2])
        } finally {
            blue.recycle()
            red.recycle()
        }
    }

    @Test
    fun multicolorModeUsesSeveralVisibleHueFamiliesAcrossTheEdges() {
        val image = render(renderer(ControlEffectsConfig(colorMode = ControlEdgeColorMode.MULTICOLOR)))
        try {
            val hues = mutableSetOf<Int>()
            val hsv = FloatArray(3)
            for (y in 40 until HEIGHT - 40 step 8) {
                for (x in listOf(8, WIDTH - 9)) {
                    val color = image.getPixel(x, y)
                    Color.colorToHSV(color, hsv)
                    if (Color.alpha(color) > 20 && hsv[1] > 0.25f) hues += (hsv[0] / 60f).toInt()
                }
            }
            for (x in 40 until WIDTH - 40 step 8) {
                for (y in listOf(8, HEIGHT - 9)) {
                    val color = image.getPixel(x, y)
                    Color.colorToHSV(color, hsv)
                    if (Color.alpha(color) > 20 && hsv[1] > 0.25f) hues += (hsv[0] / 60f).toInt()
                }
            }
            assertTrue("Multicolor must produce more than one blue edge hue", hues.size >= 3)
        } finally {
            image.recycle()
        }
    }

    @Test
    fun multicolorRespectsReducedMotionEvenAtMaximumSpeedAndRange() {
        val renderer =
            renderer(
                ControlEffectsConfig(
                    colorMode = ControlEdgeColorMode.MULTICOLOR,
                    motionRange = ControlEffectsConfig.MAX_MOTION_RANGE,
                    speed = ControlEffectsConfig.MAX_SPEED,
                ),
            )
        val first = render(renderer, now = 0L)
        val later = render(renderer, now = 18000L)
        try {
            val before = IntArray(WIDTH * HEIGHT)
            val after = IntArray(before.size)
            first.getPixels(before, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            later.getPixels(after, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            assertArrayEquals("Reduced motion freezes both color flow and edge geometry", before, after)
        } finally {
            first.recycle()
            later.recycle()
        }
    }

    @Test
    fun widthAndIntensityControlsChangeTheRenderedReachAndLightMass() {
        val narrow =
            ControlEffectsConfig(
                colorMode = ControlEdgeColorMode.BLUE,
                glowWidthDp = ControlEffectsConfig.MIN_GLOW_WIDTH_DP,
                intensity = ControlEffectsConfig.MAX_INTENSITY,
                motionRange = 0f,
            )
        val images =
            listOf(
                render(renderer(narrow)),
                render(renderer(narrow.copy(glowWidthDp = ControlEffectsConfig.MAX_GLOW_WIDTH_DP))),
                render(renderer(narrow.copy(intensity = ControlEffectsConfig.MIN_INTENSITY))),
            )
        try {
            assertTrue(
                "Increasing width must illuminate further inside the display",
                innerBandAlpha(images[1]) > innerBandAlpha(images[0]),
            )
            assertTrue("Increasing intensity must increase rendered light", alphaMass(images[0]) > alphaMass(images[2]))
        } finally {
            images.forEach { it.recycle() }
        }
    }

    @Test
    fun extremeAppearanceValuesKeepTheCenterClearInBothOrientations() {
        val maximum =
            ControlEffectsConfig(
                glowWidthDp = Float.MAX_VALUE,
                intensity = Float.MAX_VALUE,
                motionRange = Float.MAX_VALUE,
                speed = Float.MAX_VALUE,
            )
        for ((width, height) in listOf(WIDTH to HEIGHT, HEIGHT to WIDTH)) {
            val renderer =
                ControlEdgeRenderer(1f).apply {
                    updateConfig(maximum)
                    resize(width, height)
                }
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                renderer.draw(Canvas(image), 2100L, active = true, animate = true)
                for (y in height / 2 - 8..height / 2 + 8) {
                    for (x in width / 2 - 8..width / 2 + 8) {
                        assertEquals(
                            "Even extreme settings must preserve the content center",
                            Color.TRANSPARENT,
                            image.getPixel(x, y),
                        )
                    }
                }
            } finally {
                image.recycle()
            }
        }
    }

    @Test
    fun changingLiveViewAppearanceDoesNotClearRealCursorFeedback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = ControlEffectsView(instrumentation.targetContext)
            view.layout(0, 0, WIDTH, HEIGHT)
            val path = Path().apply { moveTo(WIDTH / 2f, HEIGHT / 2f) }
            val gesture =
                GestureDescription
                    .Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 1000L))
                    .build()
            view.updateAction("long_press")
            view.showGesture(gesture)
            view.config = ControlEffectsConfig(colorMode = ControlEdgeColorMode.CUSTOM, customColorArgb = Color.MAGENTA)
            val image = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(image))
                var ink = 0
                for (y in HEIGHT / 2 until HEIGHT / 2 + 20) {
                    for (x in WIDTH / 2 until WIDTH / 2 + 14) {
                        if (Color.alpha(image.getPixel(x, y)) > 0) ink++
                    }
                }
                assertTrue("Appearance updates must preserve an active real cursor at the content center", ink > 0)
            } finally {
                image.recycle()
            }
        }
    }

    private fun renderer(config: ControlEffectsConfig): ControlEdgeRenderer =
        ControlEdgeRenderer(1f).apply {
            updateConfig(config)
            resize(WIDTH, HEIGHT)
        }

    private fun render(
        renderer: ControlEdgeRenderer,
        now: Long = 1500L,
    ): Bitmap =
        Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).also {
            renderer.draw(Canvas(it), now, active = true, animate = false)
        }

    private fun edgeChannelMass(image: Bitmap): LongArray {
        val totals = LongArray(3)
        for (y in 40 until HEIGHT - 40 step 4) {
            val color = image.getPixel(8, y)
            val alpha = Color.alpha(color)
            totals[0] += Color.red(color).toLong() * alpha
            totals[1] += Color.green(color).toLong() * alpha
            totals[2] += Color.blue(color).toLong() * alpha
        }
        return totals
    }

    private fun innerBandAlpha(image: Bitmap): Long {
        var total = 0L
        for (y in HEIGHT / 4 until HEIGHT * 3 / 4 step 2) {
            for (x in 40 until WIDTH / 2) total += Color.alpha(image.getPixel(x, y))
        }
        return total
    }

    private fun alphaMass(image: Bitmap): Long {
        var total = 0L
        for (y in 0 until HEIGHT step 2) {
            for (x in 0 until WIDTH step 2) total += Color.alpha(image.getPixel(x, y))
        }
        return total
    }

    companion object {
        private const val WIDTH = 360
        private const val HEIGHT = 720
    }
}
