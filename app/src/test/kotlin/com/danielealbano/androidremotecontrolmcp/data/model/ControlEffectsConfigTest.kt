package com.danielealbano.androidremotecontrolmcp.data.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ControlEffectsConfigTest {
    @Test
    fun `defaults use multicolor with a visible wide glow`() {
        val defaults = ControlEffectsConfig()
        assertEquals(ControlEdgeColorMode.MULTICOLOR, defaults.colorMode)
        assertEquals(56f, defaults.glowWidthDp)
        assertEquals(0.9f, defaults.intensity)
        assertEquals(1.2f, defaults.motionRange)
        assertEquals(1f, defaults.speed)
        assertEquals(defaults, defaults.normalized())
    }

    @Test
    fun `finite out of range values clamp and custom colors become opaque`() {
        val normalized =
            ControlEffectsConfig(
                customColorArgb = 0x12345678,
                glowWidthDp = 200f,
                intensity = -1f,
                motionRange = 8f,
                speed = 0f,
            ).normalized()

        assertEquals(0xFF345678.toInt(), normalized.customColorArgb)
        assertEquals(96f, normalized.glowWidthDp)
        assertEquals(0.2f, normalized.intensity)
        assertEquals(1.8f, normalized.motionRange)
        assertEquals(0.5f, normalized.speed)
        assertEquals(16f, ControlEffectsConfig(glowWidthDp = -10f).normalized().glowWidthDp)
        assertEquals(1f, ControlEffectsConfig(intensity = 9f).normalized().intensity)
        assertEquals(0f, ControlEffectsConfig(motionRange = -1f).normalized().motionRange)
        assertEquals(2f, ControlEffectsConfig(speed = 99f).normalized().speed)
    }

    @Test
    fun `NaN and infinity use defaults rather than enter the renderer`() {
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val result =
                ControlEffectsConfig(
                    glowWidthDp = invalid,
                    intensity = invalid,
                    motionRange = invalid,
                    speed = invalid,
                ).normalized()
            assertEquals(ControlEffectsConfig(), result)
        }
    }

    @Test
    fun `every color mode and custom appearance round trips`() {
        for (mode in ControlEdgeColorMode.entries) {
            val config =
                ControlEffectsConfig(
                    colorMode = mode,
                    customColorArgb = 0xFFAB12CD.toInt(),
                    glowWidthDp = 72f,
                    intensity = 0.65f,
                    motionRange = 0f,
                    speed = 1.5f,
                )
            assertEquals(config, ControlEffectsConfig.fromJsonOrDefault(config.toJson()))
        }
    }

    @Test
    fun `missing malformed and non object settings recover to defaults`() {
        for (json in listOf(null, "", "invalid json", "[]", "null", "{}")) {
            assertEquals(ControlEffectsConfig(), ControlEffectsConfig.fromJsonOrDefault(json))
        }
    }

    @Test
    fun `invalid fields fall back individually while preserving valid user choices`() {
        val json =
            """
            {
              "colorMode": "FUTURE_MODE",
              "customColorArgb": {},
              "glowWidthDp": 80,
              "intensity": "NaN",
              "motionRange": false,
              "speed": 1.8,
              "futureSetting": {"ignored": true}
            }
            """.trimIndent()
        assertEquals(
            ControlEffectsConfig(glowWidthDp = 80f, speed = 1.8f),
            ControlEffectsConfig.fromJsonOrDefault(json),
        )
    }

    @Test
    fun `writing invalid settings always serializes their normalized form`() {
        val config = ControlEffectsConfig(colorMode = ControlEdgeColorMode.CUSTOM, speed = Float.NaN, intensity = 10f)
        assertEquals(config.normalized(), ControlEffectsConfig.fromJsonOrDefault(config.toJson()))
    }
}
