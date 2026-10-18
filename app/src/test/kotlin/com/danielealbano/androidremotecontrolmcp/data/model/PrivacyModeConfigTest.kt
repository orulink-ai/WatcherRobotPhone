package com.danielealbano.androidremotecontrolmcp.data.model

import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PrivacyModeConfig")
class PrivacyModeConfigTest {
    @Test
    fun `defaults are enabled false all categories on pseudonymize hashed`() {
        val config = PrivacyModeConfig()

        assertFalse(config.enabled)
        assertEquals(emptySet<PiiCategory>(), config.disabledCategories)
        assertEquals(RedactionMode.PSEUDONYMIZE, config.redactionMode)
        assertEquals(PlaceholderFormat.HASHED, config.placeholderFormat)
        assertEquals(PiiCategory.entries.toSet(), config.enabledCategories())
        PiiCategory.entries.forEach { assertTrue(config.isCategoryEnabled(it)) }
    }

    @Test
    fun `toJson fromJson round trip`() {
        val config =
            PrivacyModeConfig(
                enabled = true,
                disabledCategories = setOf(PiiCategory.NAMES, PiiCategory.EMAILS),
                redactionMode = RedactionMode.REDACT,
                placeholderFormat = PlaceholderFormat.NUMBERED,
            )

        val restored = PrivacyModeConfig.fromJsonOrDefault(config.toJson())

        assertEquals(config, restored)
    }

    @Test
    fun `fromJsonOrDefault returns default on null and garbage`() {
        assertEquals(PrivacyModeConfig(), PrivacyModeConfig.fromJsonOrDefault(null))
        assertEquals(PrivacyModeConfig(), PrivacyModeConfig.fromJsonOrDefault("not-json"))
    }

    @Test
    fun `modelRequired true when enabled and a model category on`() {
        val config = PrivacyModeConfig(enabled = true)

        assertTrue(config.modelRequired())
    }

    @Test
    fun `modelRequired false when model categories disabled`() {
        val config =
            PrivacyModeConfig(
                enabled = true,
                disabledCategories = setOf(PiiCategory.NAMES, PiiCategory.ADDRESSES, PiiCategory.NATIONAL_IDS),
            )

        assertFalse(config.modelRequired())
    }

    @Test
    fun `modelRequired false when privacy disabled`() {
        val config = PrivacyModeConfig(enabled = false)

        assertFalse(config.modelRequired())
    }
}
