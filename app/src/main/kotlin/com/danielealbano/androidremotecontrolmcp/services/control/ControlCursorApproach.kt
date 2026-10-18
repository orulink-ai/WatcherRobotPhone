package com.danielealbano.androidremotecontrolmcp.services.control

import kotlin.math.hypot
import kotlin.math.roundToLong

internal data class CursorApproach(
    val from: CursorPoint,
    val to: CursorPoint,
    val startedAt: Long,
    val durationMillis: Long,
) {
    fun sample(now: Long): CursorPoint {
        val progress =
            if (durationMillis == 0L) 1f else ((now - startedAt).toFloat() / durationMillis).coerceIn(0f, 1f)
        val eased = progress * progress * (SMOOTHSTEP_FACTOR - 2f * progress)
        return CursorPoint(from.x + (to.x - from.x) * eased, from.y + (to.y - from.y) * eased)
    }
}

private const val SMOOTHSTEP_FACTOR = 3f

/** Pre-dispatch visualization only. The endpoint always remains the requested screen coordinate. */
internal object ControlCursorApproach {
    fun plan(
        previous: CursorPoint?,
        target: CursorPoint,
        viewport: CursorSize,
        density: Float,
        animate: Boolean,
        startedAt: Long,
    ): CursorApproach {
        val scale = density.coerceAtLeast(1f)
        val offsetX = if (target.x < viewport.width / 2f) FIRST_OFFSET_X_DP else -FIRST_OFFSET_X_DP
        val offsetY = if (target.y < viewport.height / 2f) FIRST_OFFSET_Y_DP else -FIRST_OFFSET_Y_DP
        val nearby = CursorPoint(target.x + offsetX * scale, target.y + offsetY * scale)
        val from = clamp(previous ?: nearby, viewport)
        val to = clamp(target, viewport)
        val distance = hypot(to.x - from.x, to.y - from.y) / scale
        val duration =
            if (!animate || distance < 1f) {
                0L
            } else {
                (MIN_DURATION_MS + (distance / FULL_DISTANCE_DP).coerceAtMost(1f) * DURATION_RANGE_MS).roundToLong()
            }
        return CursorApproach(from, to, startedAt, duration)
    }

    private fun clamp(
        point: CursorPoint,
        viewport: CursorSize,
    ): CursorPoint =
        CursorPoint(
            point.x.coerceIn(0f, viewport.width.coerceAtLeast(0f)),
            point.y.coerceIn(0f, viewport.height.coerceAtLeast(0f)),
        )

    private const val FIRST_OFFSET_X_DP = 32f
    private const val FIRST_OFFSET_Y_DP = 20f
    private const val MIN_DURATION_MS = 180f
    private const val DURATION_RANGE_MS = 120f
    private const val FULL_DISTANCE_DP = 360f
}
