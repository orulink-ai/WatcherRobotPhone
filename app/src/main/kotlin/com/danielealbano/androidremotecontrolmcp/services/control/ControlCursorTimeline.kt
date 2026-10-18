package com.danielealbano.androidremotecontrolmcp.services.control

internal data class ControlCursorFrame(
    val progress: Float,
    val alpha: Float,
)

/** Feedback follows the actual dispatch clock, then expires; it never starts an action. */
internal object ControlCursorTimeline {
    const val HOLD_MS = 500L
    const val FADE_MS = 180L

    fun sample(
        elapsed: Long,
        delay: Long,
        duration: Long,
    ): ControlCursorFrame? {
        val time = elapsed - delay
        val movement = duration.coerceAtLeast(0L)
        val fadeStarts = movement + HOLD_MS
        if (time < 0L || time >= fadeStarts + FADE_MS) return null
        val progress = if (movement == 0L) 1f else (time.toFloat() / movement).coerceIn(0f, 1f)
        val alpha = 1f - (time - fadeStarts).coerceAtLeast(0L).toFloat() / FADE_MS
        return ControlCursorFrame(progress, alpha)
    }

    fun hasPendingStroke(
        elapsed: Long,
        delay: Long,
    ): Boolean = elapsed < delay
}
