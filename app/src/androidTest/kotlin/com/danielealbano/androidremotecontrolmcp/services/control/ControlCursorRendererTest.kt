package com.danielealbano.androidremotecontrolmcp.services.control

import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses Android's native PathMeasure and Canvas; no gestures are sent to the device. */
class ControlCursorRendererTest {
    private val renderer = ControlCursorRenderer(InstrumentationRegistry.getInstrumentation().targetContext) { 1000L }

    @Test
    fun actionNameAloneDoesNotFabricateACursor() {
        renderer.updateAction("tap")
        withBitmap { bitmap ->
            assertFalse(renderer.draw(Canvas(bitmap), 1000L, bitmap.width, bitmap.height))
            assertFalse(hasInk(bitmap, 0, 0, bitmap.width, bitmap.height))
        }
    }

    @Test
    fun zeroLengthTapDrawsAtItsRealNonzeroPosition() {
        renderer.showGesture(gesture(Path().apply { moveTo(80f, 120f) }, 80L))
        withBitmap { bitmap ->
            assertTrue(renderer.draw(Canvas(bitmap), 1000L, bitmap.width, bitmap.height))
            assertTrue(hasInk(bitmap, 80, 120, 96, 138))
            assertFalse(hasInk(bitmap, 0, 0, 20, 20))
        }
    }

    @Test
    fun movingCursorUsesTheRealStrokeMidpoint() {
        val path =
            Path().apply {
                moveTo(20f, 100f)
                lineTo(120f, 100f)
            }
        renderer.showGesture(gesture(path, 1000L))
        withBitmap { bitmap ->
            assertTrue(renderer.draw(Canvas(bitmap), 1500L, bitmap.width, bitmap.height))
            assertTrue(hasInk(bitmap, 70, 100, 85, 115))
            assertFalse(hasInk(bitmap, 20, 100, 35, 115))
        }
    }

    @Test
    fun expiredOrClearedFeedbackLeavesNoIdleMouseOrLabel() {
        val tap = gesture(Path().apply { moveTo(80f, 120f) }, 80L)
        renderer.showGesture(tap)
        withBitmap { bitmap ->
            assertFalse(renderer.draw(Canvas(bitmap), 2000L, bitmap.width, bitmap.height))
            assertFalse(hasInk(bitmap, 0, 0, bitmap.width, bitmap.height))
        }
        renderer.showGesture(tap)
        renderer.clear()
        withBitmap { bitmap ->
            assertFalse(renderer.draw(Canvas(bitmap), 1001L, bitmap.width, bitmap.height))
            assertFalse(hasInk(bitmap, 0, 0, bitmap.width, bitmap.height))
        }
    }

    @Test
    fun approachCannotClaimCompletionUntilAcceptedDispatchAndSuccessfulCallback() {
        var now = 1000L
        val renderer = ControlCursorRenderer(InstrumentationRegistry.getInstrumentation().targetContext) { now }
        val tap = gesture(Path().apply { moveTo(80f, 120f) }, 80L)
        renderer.updateAction("tap")
        val duration = renderer.prepareGesture(tap, 240, 400)
        assertEquals(ControlCursorPhase.APPROACH, renderer.phase)
        renderer.completeGesture()
        assertEquals("Preparation alone cannot create success", ControlCursorPhase.APPROACH, renderer.phase)
        now += duration
        renderer.showGesture(tap)
        assertEquals(ControlCursorPhase.EXECUTING, renderer.phase)
        now += 80L
        renderer.completeGesture()
        assertEquals(ControlCursorPhase.COMPLETED, renderer.phase)
        withBitmap { bitmap -> assertTrue(renderer.draw(Canvas(bitmap), now + 900L, bitmap.width, bitmap.height)) }
        withBitmap { bitmap ->
            assertFalse(renderer.draw(Canvas(bitmap), now + 1100L, bitmap.width, bitmap.height))
        }
        assertNull(renderer.phase)
    }

    @Test
    fun clearingAnApproachLeavesNoPointerAndSuccessfulNodeActionsUseCompletionLifetime() {
        var now = 1000L
        val renderer = ControlCursorRenderer(InstrumentationRegistry.getInstrumentation().targetContext) { now }
        val bounds = Rect(60, 100, 100, 140)
        renderer.prepareTarget(bounds, 240, 400)
        renderer.clear()
        withBitmap { bitmap -> assertFalse(renderer.draw(Canvas(bitmap), now, bitmap.width, bitmap.height)) }
        assertNull(renderer.phase)
        renderer.showTarget(bounds)
        assertEquals(ControlCursorPhase.COMPLETED, renderer.phase)
        now += 900L
        withBitmap { bitmap -> assertTrue(renderer.draw(Canvas(bitmap), now, bitmap.width, bitmap.height)) }
    }

    @Test
    fun multipleAcceptedStrokesKeepTheirSeparateRealPaths() {
        val gesture =
            GestureDescription
                .Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(
                        Path().apply {
                            moveTo(40f, 100f)
                            lineTo(40f, 200f)
                        },
                        0L,
                        1000L,
                    ),
                ).addStroke(
                    GestureDescription.StrokeDescription(
                        Path().apply {
                            moveTo(140f, 100f)
                            lineTo(140f, 200f)
                        },
                        0L,
                        1000L,
                    ),
                ).build()
        renderer.showGesture(gesture)
        withBitmap { bitmap ->
            assertTrue(renderer.draw(Canvas(bitmap), 1500L, bitmap.width, bitmap.height))
            assertTrue(hasInk(bitmap, 40, 150, 54, 165))
            assertTrue(hasInk(bitmap, 140, 150, 154, 165))
            assertFalse(hasInk(bitmap, 40, 100, 54, 115))
        }
    }

    private fun gesture(
        path: Path,
        duration: Long,
    ): GestureDescription =
        GestureDescription
            .Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()

    private fun withBitmap(block: (Bitmap) -> Unit) {
        val bitmap = Bitmap.createBitmap(240, 400, Bitmap.Config.ARGB_8888)
        try {
            block(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun hasInk(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ): Boolean = (top until bottom).any { y -> (left until right).any { x -> Color.alpha(bitmap.getPixel(x, y)) != 0 } }
}
