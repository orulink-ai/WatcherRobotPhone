package com.danielealbano.androidremotecontrolmcp.services.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ControlCursorApproachTest {
    @Test
    fun `a subsequent target begins at the previous tip and arrives at the actual target`() {
        val from = CursorPoint(40f, 100f)
        val to = CursorPoint(240f, 300f)
        val approach = ControlCursorApproach.plan(from, to, CursorSize(400f, 800f), 1f, true, 1000L)
        assertEquals(from, approach.sample(1000L))
        assertEquals(to, approach.sample(1000L + approach.durationMillis))
        assertTrue(approach.durationMillis in 180L..320L)
    }

    @Test
    fun `the first pointer starts nearby inside the viewport even at display corners`() {
        val viewport = CursorSize(400f, 800f)
        for (target in listOf(CursorPoint(0f, 0f), CursorPoint(399f, 799f))) {
            val approach = ControlCursorApproach.plan(null, target, viewport, 2f, true, 0L)
            assertTrue(approach.from.x in 0f..400f && approach.from.y in 0f..800f)
            assertTrue(approach.from != target)
            assertEquals(target, approach.to)
        }
    }

    @Test
    fun `movement eases in and out without overshooting its destination`() {
        val approach =
            ControlCursorApproach.plan(
                CursorPoint(0f, 0f),
                CursorPoint(360f, 0f),
                CursorSize(400f, 800f),
                1f,
                true,
                0L,
            )
        val quarter = approach.sample(approach.durationMillis / 4L)
        val half = approach.sample(approach.durationMillis / 2L)
        val end = approach.sample(approach.durationMillis + 100L)
        assertTrue(quarter.x in 0f..90f)
        assertEquals(180f, half.x, 0.01f)
        assertEquals(approach.to, end)
    }

    @Test
    fun `reduced motion reaches the target immediately without delaying actual dispatch`() {
        val target = CursorPoint(240f, 300f)
        val approach =
            ControlCursorApproach.plan(
                CursorPoint(40f, 100f),
                target,
                CursorSize(400f, 800f),
                1f,
                false,
                0L,
            )
        assertEquals(0L, approach.durationMillis)
        assertEquals(target, approach.sample(0L))
    }

    @Test
    fun `a stationary pointer needs no approach delay`() {
        val target = CursorPoint(240f, 300f)
        val approach = ControlCursorApproach.plan(target, target, CursorSize(400f, 800f), 1f, true, 0L)
        assertEquals(0L, approach.durationMillis)
    }
}
