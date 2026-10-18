package com.danielealbano.androidremotecontrolmcp.services.control

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class ControlEdgeSide { LEFT, RIGHT, TOP, BOTTOM }

internal data class ControlEdgeLight(
    val opacity: Float,
    val widthScale: Float,
    val focus: Float,
    val spread: Float,
)

/** Independent smooth rhythms, reconstructed from the visual reference rather than unpublished ego UI code. */
internal object ControlEdgeMotion {
    fun sample(
        side: ControlEdgeSide,
        nowMillis: Long,
        active: Boolean,
        animate: Boolean,
        motionRange: Float = DEFAULT_MOTION_RANGE,
    ): ControlEdgeLight = sample(side, nowMillis, if (active) 1f else 0f, animate, motionRange)

    fun sample(
        side: ControlEdgeSide,
        nowMillis: Long,
        activity: Float,
        animate: Boolean,
        motionRange: Float = DEFAULT_MOTION_RANGE,
    ): ControlEdgeLight {
        val strength = if (activity.isFinite()) activity.coerceIn(0f, 1f) else 0f
        if (!animate) {
            return ControlEdgeLight(
                blend(STATIC_IDLE, STATIC_ACTIVE, strength),
                1f,
                WAVE_MIDPOINT.toFloat(),
                STATIC_SPREAD,
            )
        }
        val rhythm = rhythms[side.ordinal]
        val primary = wave(nowMillis, rhythm.primary, rhythm.phase)
        val secondary = wave(nowMillis, rhythm.secondary, rhythm.phase + SECONDARY_PHASE)
        val tertiary = wave(nowMillis, rhythm.tertiary, rhythm.phase + TERTIARY_PHASE)
        val mix = PRIMARY_WEIGHT * primary + SECONDARY_WEIGHT * secondary + TERTIARY_WEIGHT * tertiary
        val range = if (motionRange.isFinite()) motionRange.coerceIn(0f, MAX_MOTION_RANGE) else DEFAULT_MOTION_RANGE
        val amplitude = sqrt(range / MAX_MOTION_RANGE)
        val widthWave = WIDTH_SECONDARY_WEIGHT * secondary + WIDTH_TERTIARY_WEIGHT * tertiary
        return ControlEdgeLight(
            opacity =
                (blend(IDLE_BASE, ACTIVE_BASE, strength) + blend(IDLE_RANGE, ACTIVE_RANGE, strength) * mix)
                    .coerceIn(0f, 1f),
            widthScale =
                (1f + amplitude * (WIDTH_OFFSET + WIDTH_RANGE * widthWave)).coerceIn(MIN_WIDTH, MAX_WIDTH),
            focus =
                (
                    WAVE_MIDPOINT.toFloat() + amplitude * FOCUS_TRAVEL *
                        (wave(nowMillis, rhythm.travel, rhythm.phase) - WAVE_MIDPOINT.toFloat())
                ).coerceIn(FOCUS_MARGIN, 1f - FOCUS_MARGIN),
            spread =
                (STATIC_SPREAD + amplitude * (SPREAD_OFFSET + SPREAD_RANGE * primary))
                    .coerceIn(MIN_SPREAD, MAX_SPREAD),
        )
    }

    private fun wave(
        now: Long,
        period: Long,
        phase: Double,
    ): Float {
        val angle = (now % period).toDouble() / period * 2.0 * PI + phase
        return (WAVE_MIDPOINT + WAVE_MIDPOINT * sin(angle)).toFloat()
    }

    private fun blend(
        start: Float,
        end: Float,
        progress: Float,
    ): Float = start + (end - start) * progress

    private data class Rhythm(
        val primary: Long,
        val secondary: Long,
        val tertiary: Long,
        val travel: Long,
        val phase: Double,
    )

    // Deliberately distinct periods and phases prevent the four sides from sharing one loop.
    @Suppress("MagicNumber")
    private val rhythms =
        listOf(
            Rhythm(6130L, 2770L, 11390L, 12310L, 0.15),
            Rhythm(8370L, 3910L, 13010L, 15430L, 1.7),
            Rhythm(7090L, 4570L, 15430L, 17890L, 3.25),
            Rhythm(9730L, 3310L, 17890L, 19910L, 4.8),
        )
    private const val ACTIVE_BASE = 0.34f
    private const val ACTIVE_RANGE = 0.58f
    private const val IDLE_BASE = 0.20f
    private const val IDLE_RANGE = 0.28f
    private const val STATIC_ACTIVE = 0.62f
    private const val STATIC_IDLE = 0.34f
    private const val STATIC_SPREAD = 0.3f
    private const val PRIMARY_WEIGHT = 0.46f
    private const val SECONDARY_WEIGHT = 0.34f
    private const val TERTIARY_WEIGHT = 0.20f
    private const val DEFAULT_MOTION_RANGE = 1.2f
    private const val MAX_MOTION_RANGE = 1.8f
    private const val MIN_WIDTH = 0.45f
    private const val MAX_WIDTH = 2f
    private const val WIDTH_OFFSET = -0.55f
    private const val WIDTH_RANGE = 1.55f
    private const val WIDTH_SECONDARY_WEIGHT = 0.6f
    private const val WIDTH_TERTIARY_WEIGHT = 0.4f
    private const val FOCUS_MARGIN = 0.05f
    private const val FOCUS_TRAVEL = 0.9f
    private const val SPREAD_OFFSET = -0.1f
    private const val SPREAD_RANGE = 0.35f
    private const val MIN_SPREAD = 0.2f
    private const val MAX_SPREAD = 0.55f
    private const val SECONDARY_PHASE = 1.1
    private const val TERTIARY_PHASE = 2.4
    private const val WAVE_MIDPOINT = 0.5
}
