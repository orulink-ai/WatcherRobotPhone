package com.danielealbano.androidremotecontrolmcp.services.control

import kotlin.math.PI
import kotlin.math.sin

internal data class ControlCursorFrame(
    val progress: Float,
    val alpha: Float,
)

internal data class ControlCursorClickEffect(
    val progress: Float,
    val scale: Float,
)

/** Feedback follows the actual dispatch clock, then expires; it never starts an action. */
internal object ControlCursorTimeline {
    const val HOLD_MS = 500L
    const val FADE_MS = 180L
    const val COMPLETION_HOLD_MS = 900L
    const val COMPLETION_FADE_MS = 200L

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

    fun completionAlpha(elapsed: Long): Float? {
        if (elapsed < 0L || elapsed >= COMPLETION_HOLD_MS + COMPLETION_FADE_MS) return null
        return 1f - (elapsed - COMPLETION_HOLD_MS).coerceAtLeast(0L).toFloat() / COMPLETION_FADE_MS
    }

    fun clickEffect(elapsed: Long): ControlCursorClickEffect? {
        if (elapsed < 0L || elapsed >= CLICK_EFFECT_MS) return null
        val progress = elapsed.toFloat() / CLICK_EFFECT_MS
        val scale = 1f - PRESS_DEPTH * sin(progress * PI).toFloat()
        return ControlCursorClickEffect(progress, scale)
    }

    private const val CLICK_EFFECT_MS = 240L
    private const val PRESS_DEPTH = 0.12f
}
