package com.danielealbano.androidremotecontrolmcp.services.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlEdgeActivityTest {
    @Test
    fun `task changes interpolate continuously and settle at the new intensity`() {
        val activity = ControlEdgeActivity()
        assertEquals(0f, activity.sample(0, active = false, animate = true))
        val starting = activity.sample(16, active = true, animate = true)
        assertTrue(starting in 0f..0.08f)
        val running = activity.sample(1500, active = true, animate = true)
        assertTrue(running > 0.99f)
        val ending = activity.sample(1516, active = false, animate = true)
        assertTrue(ending < running && ending > 0.9f)
        assertTrue(activity.sample(3000, active = false, animate = true) < 0.01f)
    }

    @Test
    fun `animation disablement selects a steady intensity immediately`() {
        val activity = ControlEdgeActivity()
        assertEquals(1f, activity.sample(0, active = true, animate = false))
        assertEquals(0f, activity.sample(16, active = false, animate = false))
        assertEquals(0f, activity.sample(2000, active = false, animate = false))
    }
}
