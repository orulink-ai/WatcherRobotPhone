package com.danielealbano.androidremotecontrolmcp.services.control

import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises real Android native Path behavior, which mockable android.jar cannot reproduce. */
class GestureGeometryTest {
    @Test
    fun tapRetainsItsActualNonzeroPosition() {
        val path = Path().apply { moveTo(720f, 2100f) }
        val geometry = measureGesturePath(path)

        assertEquals(720f, geometry.originX, 0f)
        assertEquals(2100f, geometry.originY, 0f)
        assertEquals(RectF(720f, 2100f, 720f, 2100f), geometry.bounds)
        assertEquals(0f, PathMeasure(path, false).length, 0f)
    }

    @Test
    fun bottomTapIntersectsTheControlPanelAfterTouchPadding() {
        val geometry = measureGesturePath(Path().apply { moveTo(720f, 2100f) })
        geometry.bounds.inset(-8f, -8f)

        assertTrue(RectF.intersects(RectF(80f, 2050f, 1000f, 2250f), geometry.bounds))
    }

    @Test
    fun originTapIsHandledWithoutMovingIt() {
        val geometry = measureGesturePath(Path().apply { moveTo(0f, 0f) })

        assertEquals(0f, geometry.originX, 0f)
        assertEquals(0f, geometry.originY, 0f)
    }

    @Test
    fun swipeKeepsItsLengthBoundsAndOrigin() {
        val path =
            Path().apply {
                moveTo(200f, 1600f)
                lineTo(200f, 400f)
            }
        val geometry = measureGesturePath(path)

        assertEquals(200f, geometry.originX, 0f)
        assertEquals(1600f, geometry.originY, 0f)
        assertEquals(1200f, geometry.measure.length, 0f)
        assertEquals(RectF(200f, 400f, 200f, 1600f), geometry.bounds)
    }
}
