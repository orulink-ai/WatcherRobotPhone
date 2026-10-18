package com.danielealbano.androidremotecontrolmcp.services.control

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class ControlEdgeMotionTest {
    @Test
    fun `four edges evolve independently rather than breathing in unison`() {
        for (first in ControlEdgeSide.entries) {
            for (second in ControlEdgeSide.entries.filter { it.ordinal > first.ordinal }) {
                val differences =
                    (0L..30000L step 100L).map { time ->
                        abs(sample(first, time).opacity - sample(second, time).opacity)
                    }
                assertTrue(differences.average() > 0.06, "$first and $second must not share a pulse")
            }
        }
    }

    @Test
    fun `light has bounded opacity depth and moving focus over long uptimes`() {
        for (side in ControlEdgeSide.entries) {
            for (time in listOf(0L, 16L, 4800L, 60000L, 86400000L, Long.MAX_VALUE)) {
                val light = sample(side, time)
                assertTrue(light.opacity in 0f..1f)
                assertTrue(light.widthScale in 0.45f..2f)
                assertTrue(light.focus in 0.05f..0.95f)
                assertTrue(light.spread in 0.2f..0.55f)
                assertEquals(light, sample(side, time))
            }
        }
    }

    @Test
    fun `adjacent animation frames are continuous in every dimension`() {
        for (side in ControlEdgeSide.entries) {
            for (time in 0L..20000L step 31L) {
                val previous = sample(side, time)
                val next = sample(side, time + 16)
                assertTrue(abs(next.opacity - previous.opacity) < 0.02f)
                assertTrue(abs(next.widthScale - previous.widthScale) < 0.02f)
                assertTrue(abs(next.focus - previous.focus) < 0.02f)
                assertTrue(abs(next.spread - previous.spread) < 0.02f)
            }
        }
    }

    @Test
    fun `neither brightness nor shape resets at the former shared breathing period`() {
        for (side in ControlEdgeSide.entries) {
            assertFalse(sample(side, 0) == sample(side, 4800))
            val lights = (0L..30000L step 100L).map { sample(side, it) }
            assertTrue(lights.maxOf { it.widthScale } - lights.minOf { it.widthScale } > 0.3f)
            assertTrue(lights.maxOf { it.focus } - lights.minOf { it.focus } > 0.5f)
        }
    }

    @Test
    fun `idle glow is quieter while retaining independent edge motion`() {
        for (side in ControlEdgeSide.entries) {
            val active =
                (0L..60000L step 97L).map {
                    ControlEdgeMotion.sample(side, it, active = true, animate = true).opacity
                }
            val idle =
                (0L..60000L step 97L).map {
                    ControlEdgeMotion.sample(side, it, active = false, animate = true).opacity
                }
            assertTrue(active.average() > idle.average() + 0.15)
            assertTrue(idle.max() - idle.min() > 0.1f)
        }
    }

    @Test
    fun `starting or ending a tool does not teleport the edge highlight`() {
        for (side in ControlEdgeSide.entries) {
            for (time in listOf(0L, 17352L, 86400000L)) {
                val active = ControlEdgeMotion.sample(side, time, active = true, animate = true)
                val idle = ControlEdgeMotion.sample(side, time, active = false, animate = true)
                assertEquals(active.focus, idle.focus)
                assertEquals(active.widthScale, idle.widthScale)
                assertEquals(active.spread, idle.spread)
            }
        }
    }

    @Test
    fun `reduced motion freezes brightness focus and depth`() {
        for (side in ControlEdgeSide.entries) {
            for (active in listOf(false, true)) {
                assertEquals(
                    ControlEdgeMotion.sample(side, 0L, active, animate = false),
                    ControlEdgeMotion.sample(side, 18000L, active, animate = false),
                )
            }
        }
    }

    @Test
    fun `zero range holds centered fixed geometry across all phases`() {
        for (side in ControlEdgeSide.entries) {
            for (time in 0L..60000L step 137L) {
                val light = ControlEdgeMotion.sample(side, time, activity = 1f, animate = true, motionRange = 0f)
                assertEquals(1f, light.widthScale)
                assertEquals(0.5f, light.focus)
                assertEquals(0.3f, light.spread)
                assertTrue(light.opacity in 0f..1f)
            }
        }
    }

    @Test
    fun `default range produces visibly wider depth changes than the old narrow rhythm`() {
        for (side in ControlEdgeSide.entries) {
            val lights = (0L..120000L step 67L).map { sample(side, it) }
            assertTrue(lights.maxOf { it.widthScale } > 1.7f)
            assertTrue(lights.minOf { it.widthScale } < 0.7f)
            assertTrue(lights.maxOf { it.widthScale } - lights.minOf { it.widthScale } > 1f)
        }
    }

    @Test
    fun `maximum range expands coverage without escaping geometry or opacity limits`() {
        for (side in ControlEdgeSide.entries) {
            val lights =
                (0L..120000L step 67L).map {
                    ControlEdgeMotion.sample(side, it, activity = 1f, animate = true, motionRange = 1.8f)
                }
            assertTrue(lights.all { it.widthScale in 0.45f..2f && it.focus in 0.05f..0.95f })
            assertTrue(lights.all { it.spread in 0.2f..0.55f && it.opacity in 0f..1f })
            assertTrue(lights.maxOf { it.widthScale } > 1.85f)
            assertTrue(lights.minOf { it.widthScale } < 0.6f)
            assertTrue(lights.maxOf { it.focus } > 0.92f)
            assertTrue(lights.minOf { it.focus } < 0.08f)
        }
    }

    @Test
    fun `changing task strength never changes the configured geometric phase`() {
        for (range in listOf(0f, 1.2f, 1.8f)) {
            for (side in ControlEdgeSide.entries) {
                val idle = ControlEdgeMotion.sample(side, 17352L, activity = 0f, animate = true, motionRange = range)
                val active = ControlEdgeMotion.sample(side, 17352L, activity = 1f, animate = true, motionRange = range)
                assertEquals(idle.widthScale, active.widthScale)
                assertEquals(idle.focus, active.focus)
                assertEquals(idle.spread, active.spread)
            }
        }
    }

    private fun sample(
        side: ControlEdgeSide,
        time: Long,
    ): ControlEdgeLight = ControlEdgeMotion.sample(side, time, active = true, animate = true)
}
