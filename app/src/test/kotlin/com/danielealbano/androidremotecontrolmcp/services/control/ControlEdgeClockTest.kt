package com.danielealbano.androidremotecontrolmcp.services.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ControlEdgeClockTest {
    @Test
    fun `first frame starts a local monotonic clock regardless of system uptime`() {
        val clock = ControlEdgeClock()
        assertEquals(0L, clock.sample(86400000L, speed = 1f, animate = true))
        assertEquals(16L, clock.sample(86400016L, speed = 1f, animate = true))
        assertEquals(32L, clock.sample(86400032L, speed = 1f, animate = true))
    }

    @Test
    fun `changing speed retains the current phase and affects only future intervals`() {
        val clock = ControlEdgeClock()
        assertEquals(0L, clock.sample(0L, speed = 1f, animate = true))
        assertEquals(100L, clock.sample(100L, speed = 1f, animate = true))
        assertEquals(100L, clock.sample(100L, speed = 2f, animate = true))
        assertEquals(132L, clock.sample(116L, speed = 2f, animate = true))
        assertEquals(132L, clock.sample(116L, speed = 0.5f, animate = true))
        assertEquals(140L, clock.sample(132L, speed = 0.5f, animate = true))
    }

    @Test
    fun `reduced motion freezes phase and resumes without consuming the paused gap`() {
        val clock = ControlEdgeClock()
        clock.sample(0L, speed = 1f, animate = true)
        assertEquals(16L, clock.sample(16L, speed = 1f, animate = true))
        assertEquals(32L, clock.sample(32L, speed = 1f, animate = false))
        assertEquals(32L, clock.sample(1000L, speed = 2f, animate = false))
        assertEquals(32L, clock.sample(5000L, speed = 2f, animate = true))
        assertEquals(64L, clock.sample(5016L, speed = 2f, animate = true))
    }

    @Test
    fun `a reversed time sample cannot rewind or count the same interval twice`() {
        val clock = ControlEdgeClock()
        clock.sample(1000L, speed = 1f, animate = true)
        assertEquals(100L, clock.sample(1100L, speed = 1f, animate = true))
        assertEquals(100L, clock.sample(1050L, speed = 1f, animate = true))
        assertEquals(110L, clock.sample(1110L, speed = 1f, animate = true))
    }

    @Test
    fun `invalid speed cannot poison later valid animation frames`() {
        val clock = ControlEdgeClock()
        clock.sample(0L, speed = Float.NaN, animate = true)
        assertEquals(16L, clock.sample(16L, speed = 1f, animate = true))
        assertEquals(32L, clock.sample(32L, speed = Float.POSITIVE_INFINITY, animate = true))
        assertEquals(48L, clock.sample(48L, speed = 1f, animate = true))
    }
}
