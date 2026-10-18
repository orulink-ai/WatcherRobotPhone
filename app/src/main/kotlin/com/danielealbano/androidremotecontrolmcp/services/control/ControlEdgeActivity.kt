package com.danielealbano.androidremotecontrolmcp.services.control

import kotlin.math.exp

/** Keeps task start/end from abruptly changing the strength of a continuous edge trajectory. */
internal class ControlEdgeActivity {
    private var previousFrameAt: Long? = null
    private var intensity = 0f

    fun sample(
        now: Long,
        active: Boolean,
        animate: Boolean,
    ): Float {
        val target = if (active) 1f else 0f
        val previous = previousFrameAt
        intensity =
            if (previous == null || !animate) {
                target
            } else {
                val elapsed = (now - previous).coerceAtLeast(0L)
                val blend = (1.0 - exp(-elapsed.toDouble() / TRANSITION_MS)).toFloat()
                intensity + (target - intensity) * blend
            }
        previousFrameAt = now
        return intensity
    }

    private companion object {
        const val TRANSITION_MS = 250.0
    }
}
