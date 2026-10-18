package com.danielealbano.androidremotecontrolmcp.services.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlCursorGeometryTest {
    @Test
    fun `label normally follows below and to the right of the actual cursor`() {
        val label =
            ControlCursorGeometry.placeLabel(
                CursorPoint(100f, 200f),
                CursorSize(120f, 32f),
                CursorSize(400f, 800f),
            )

        assertEquals(CursorBox(112f, 233f, 120f, 32f), label)
        assertTrue(label.top >= 200f + 25f + 8f)
    }

    @Test
    fun `label flips at bottom right without moving the cursor hit point`() {
        val point = CursorPoint(399f, 799f)
        val label = ControlCursorGeometry.placeLabel(point, CursorSize(120f, 32f), CursorSize(400f, 800f))

        assertEquals(CursorBox(267f, 734f, 120f, 32f), label)
        assertEquals(CursorPoint(399f, 799f), point)
    }

    @Test
    fun `oversized text is constrained within narrow displays`() {
        val label =
            ControlCursorGeometry.placeLabel(
                CursorPoint(2f, 4f),
                CursorSize(500f, 32f),
                CursorSize(160f, 240f),
            )

        assertEquals(8f, label.left)
        assertEquals(144f, label.width)
        assertTrue(label.left + label.width <= 152f)
    }

    @Test
    fun `layout remains bounded when viewport is smaller than normal margins`() {
        val label =
            ControlCursorGeometry.placeLabel(
                CursorPoint(0f, 0f),
                CursorSize(120f, 32f),
                CursorSize(6f, 4f),
            )

        assertTrue(label.left >= 0f && label.top >= 0f)
        assertTrue(label.left + label.width <= 6f)
        assertTrue(label.top + label.height <= 4f)
    }
}
