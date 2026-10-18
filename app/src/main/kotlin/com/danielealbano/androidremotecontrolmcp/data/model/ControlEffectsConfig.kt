package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

enum class ControlEdgeColorMode { BLUE, MULTICOLOR, CUSTOM }

/** Persisted appearance choices shared by the settings screen and the live control overlay. */
data class ControlEffectsConfig(
    val colorMode: ControlEdgeColorMode = ControlEdgeColorMode.MULTICOLOR,
    val customColorArgb: Int = DEFAULT_CUSTOM_COLOR_ARGB,
    val glowWidthDp: Float = DEFAULT_GLOW_WIDTH_DP,
    val intensity: Float = DEFAULT_INTENSITY,
    val motionRange: Float = DEFAULT_MOTION_RANGE,
    val speed: Float = DEFAULT_SPEED,
) {
    /** Invalid persisted or caller-supplied numbers never reach drawing or animation calculations. */
    fun normalized(): ControlEffectsConfig =
        copy(
            customColorArgb = customColorArgb or OPAQUE_ALPHA,
            glowWidthDp = finiteClamped(glowWidthDp, DEFAULT_GLOW_WIDTH_DP, MIN_GLOW_WIDTH_DP, MAX_GLOW_WIDTH_DP),
            intensity = finiteClamped(intensity, DEFAULT_INTENSITY, MIN_INTENSITY, MAX_INTENSITY),
            motionRange = finiteClamped(motionRange, DEFAULT_MOTION_RANGE, MIN_MOTION_RANGE, MAX_MOTION_RANGE),
            speed = finiteClamped(speed, DEFAULT_SPEED, MIN_SPEED, MAX_SPEED),
        )

    fun toJson(): String {
        val config = normalized()
        return buildJsonObject {
            put("colorMode", config.colorMode.name)
            put("customColorArgb", config.customColorArgb)
            put("glowWidthDp", config.glowWidthDp)
            put("intensity", config.intensity)
            put("motionRange", config.motionRange)
            put("speed", config.speed)
        }.toString()
    }

    companion object {
        const val DEFAULT_CUSTOM_COLOR_ARGB = 0xFF3380FF.toInt()
        const val DEFAULT_GLOW_WIDTH_DP = 56f
        const val DEFAULT_INTENSITY = 0.9f
        const val DEFAULT_MOTION_RANGE = 1.2f
        const val DEFAULT_SPEED = 1f
        const val MIN_GLOW_WIDTH_DP = 16f
        const val MAX_GLOW_WIDTH_DP = 96f
        const val MIN_INTENSITY = 0.2f
        const val MAX_INTENSITY = 1f
        const val MIN_MOTION_RANGE = 0f
        const val MAX_MOTION_RANGE = 1.8f
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2f
        private const val OPAQUE_ALPHA = 0xFF000000.toInt()

        /** Each field recovers independently so a future enum or damaged value retains other choices. */
        fun fromJsonOrDefault(json: String?): ControlEffectsConfig {
            val fields = parseFields(json) ?: return ControlEffectsConfig()
            val modeName = fields.primitive("colorMode")?.contentOrNull
            val mode =
                ControlEdgeColorMode.entries
                    .firstOrNull { it.name == modeName }
                    ?: ControlEdgeColorMode.MULTICOLOR
            return ControlEffectsConfig(
                colorMode = mode,
                customColorArgb = fields.primitive("customColorArgb")?.intOrNull ?: DEFAULT_CUSTOM_COLOR_ARGB,
                glowWidthDp = fields.primitive("glowWidthDp")?.floatOrNull ?: DEFAULT_GLOW_WIDTH_DP,
                intensity = fields.primitive("intensity")?.floatOrNull ?: DEFAULT_INTENSITY,
                motionRange = fields.primitive("motionRange")?.floatOrNull ?: DEFAULT_MOTION_RANGE,
                speed = fields.primitive("speed")?.floatOrNull ?: DEFAULT_SPEED,
            ).normalized()
        }

        private fun parseFields(json: String?): JsonObject? =
            if (json.isNullOrBlank()) {
                null
            } else {
                try {
                    Json.parseToJsonElement(json) as? JsonObject
                } catch (_: SerializationException) {
                    null
                }
            }

        private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

        private fun finiteClamped(
            value: Float,
            fallback: Float,
            minimum: Float,
            maximum: Float,
        ): Float = if (value.isFinite()) value.coerceIn(minimum, maximum) else fallback
    }
}
