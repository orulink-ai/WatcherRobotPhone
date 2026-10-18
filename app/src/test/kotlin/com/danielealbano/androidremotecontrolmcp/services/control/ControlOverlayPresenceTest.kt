package com.danielealbano.androidremotecontrolmcp.services.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlOverlayPresenceTest {
    @Test
    fun `entry grows continuously and remains visible across idle samples`() {
        val presence = ControlOverlayPresence(initiallyVisible = false)
        assertEquals(0f, presence.sample(0L, visible = true, animate = true).progress)
        assertTrue(presence.sample(250L, visible = true, animate = true).progress in 0.1f..0.9f)
        val settled = presence.sample(600L, visible = true, animate = true)
        assertEquals(1f, settled.progress)
        assertFalse(settled.animating)
        assertEquals(1f, presence.sample(5000L, visible = true, animate = true).progress)
    }

    @Test
    fun `exit retracts before becoming fully hidden`() {
        val presence = ControlOverlayPresence()
        assertEquals(1f, presence.sample(0L, visible = false, animate = true).progress)
        assertTrue(presence.sample(250L, visible = false, animate = true).progress in 0.1f..0.9f)
        assertEquals(0f, presence.sample(600L, visible = false, animate = true).progress)
    }

    @Test
    fun `reversing an exit continues from the current extent`() {
        val presence = ControlOverlayPresence()
        presence.sample(0L, visible = false, animate = true)
        val before = presence.sample(200L, visible = false, animate = true).progress
        val reversed = presence.sample(200L, visible = true, animate = true).progress
        assertEquals(before, reversed)
        assertEquals(1f, presence.sample(800L, visible = true, animate = true).progress)
    }

    @Test
    fun `system reduced motion makes lifecycle transitions immediate`() {
        val presence = ControlOverlayPresence(initiallyVisible = false)
        assertEquals(1f, presence.sample(0L, visible = true, animate = false).progress)
        val hidden = presence.sample(1L, visible = false, animate = false)
        assertEquals(0f, hidden.progress)
        assertFalse(hidden.animating)
    }
}
