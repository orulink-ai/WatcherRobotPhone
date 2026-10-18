package com.danielealbano.androidremotecontrolmcp.services.control

/** A continuous visual phase clock. Speed changes affect future intervals, never the current phase. */
internal class ControlEdgeClock {
    private var previousAt: Long? = null
    private var previousSpeed = 1.0
    private var wasAnimating = false
    private var elapsedMillis = 0.0

    fun sample(
        now: Long,
        speed: Float,
        animate: Boolean,
    ): Long {
        val previous = previousAt
        if (previous != null && wasAnimating) {
            val elapsed = (now.toDouble() - previous.toDouble()).coerceAtLeast(0.0)
            elapsedMillis = (elapsedMillis + elapsed * previousSpeed).coerceAtMost(Long.MAX_VALUE.toDouble())
        }
        previousAt = if (previous == null) now else maxOf(previous, now)
        previousSpeed = if (speed.isFinite()) speed.toDouble().coerceAtLeast(0.0) else 1.0
        wasAnimating = animate
        return elapsedMillis.toLong()
    }
}
