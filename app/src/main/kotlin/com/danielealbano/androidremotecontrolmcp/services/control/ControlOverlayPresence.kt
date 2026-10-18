package com.danielealbano.androidremotecontrolmcp.services.control

import kotlin.math.abs

internal data class ControlPresenceFrame(
    val progress: Float,
    val animating: Boolean,
)

/** Explicit control entry/exit is independent of individual tool activity and its idle gaps. */
internal class ControlOverlayPresence(
    initiallyVisible: Boolean = true,
) {
    private var target = if (initiallyVisible) 1f else 0f
    private var from = target
    private var startedAt = 0L
    private var duration = 0L

    fun snap(visible: Boolean) {
        target = if (visible) 1f else 0f
        from = target
        duration = 0L
    }

    fun sample(
        now: Long,
        visible: Boolean,
        animate: Boolean,
    ): ControlPresenceFrame {
        if (!animate) {
            snap(visible)
            return ControlPresenceFrame(target, false)
        }
        val current = extent(now)
        val next = if (visible) 1f else 0f
        if (next != target) {
            from = current
            target = next
            startedAt = now
            duration = (TRANSITION_MS * abs(target - from)).toLong()
        }
        val value = extent(now)
        return ControlPresenceFrame(value, value != target)
    }

    private fun extent(now: Long): Float {
        if (duration <= 0L) return target
        val progress = ((now - startedAt).toDouble() / duration).coerceIn(0.0, 1.0).toFloat()
        val eased = progress * progress * (SMOOTHSTEP_BASE - SMOOTHSTEP_SCALE * progress)
        return if (progress >= 1f) target else from + (target - from) * eased
    }

    companion object {
        private const val TRANSITION_MS = 500L
        private const val SMOOTHSTEP_BASE = 3f
        private const val SMOOTHSTEP_SCALE = 2f
    }
}
