package com.danielealbano.androidremotecontrolmcp.services.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlCursorTimelineTest {
    @Test
    fun `cursor is absent until its actual delayed stroke begins`() {
        assertNull(ControlCursorTimeline.sample(elapsed = 199L, delay = 200L, duration = 500L))
        assertEquals(0f, ControlCursorTimeline.sample(200L, 200L, 500L)?.progress)
    }

    @Test
    fun `stroke sampling follows dispatch duration and stops at its endpoint`() {
        assertEquals(0.5f, ControlCursorTimeline.sample(450L, 200L, 500L)?.progress)
        assertEquals(1f, ControlCursorTimeline.sample(800L, 200L, 500L)?.progress)
    }

    @Test
    fun `zero length tap timing remains visible without division by zero`() {
        val frame = ControlCursorTimeline.sample(0L, 0L, 0L)!!
        assertEquals(1f, frame.progress)
        assertEquals(1f, frame.alpha)
    }

    @Test
    fun `unconfirmed execution feedback expires without creating completion`() {
        val duration = 100L
        val fadeStarts = duration + ControlCursorTimeline.HOLD_MS
        val disappears = fadeStarts + ControlCursorTimeline.FADE_MS
        assertEquals(1f, ControlCursorTimeline.sample(fadeStarts, 0L, duration)?.alpha)
        assertTrue(ControlCursorTimeline.sample(fadeStarts + 1L, 0L, duration)!!.alpha < 1f)
        assertNull(ControlCursorTimeline.sample(disappears, 0L, duration))
    }

    @Test
    fun `confirmed completion has its own nine hundred millisecond hold and two hundred millisecond fade`() {
        assertEquals(1f, ControlCursorTimeline.completionAlpha(0L))
        assertEquals(1f, ControlCursorTimeline.completionAlpha(900L))
        assertEquals(0.5f, ControlCursorTimeline.completionAlpha(1000L))
        assertNull(ControlCursorTimeline.completionAlpha(1100L))
    }

    @Test
    fun `accepted click pulse presses then releases within two hundred forty milliseconds`() {
        assertEquals(1f, ControlCursorTimeline.clickEffect(0L)!!.scale)
        assertTrue(ControlCursorTimeline.clickEffect(120L)!!.scale < 0.95f)
        assertTrue(ControlCursorTimeline.clickEffect(220L)!!.scale > ControlCursorTimeline.clickEffect(120L)!!.scale)
        assertNull(ControlCursorTimeline.clickEffect(240L))
        assertNull(ControlCursorTimeline.clickEffect(-1L))
    }
}
