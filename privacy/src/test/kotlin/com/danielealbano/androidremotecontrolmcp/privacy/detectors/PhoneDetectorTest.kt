package com.danielealbano.androidremotecontrolmcp.privacy.detectors

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Locale

@DisplayName("PhoneDetector")
class PhoneDetectorTest {
    private val detector = PhoneDetector()
    private lateinit var originalLocale: Locale

    @BeforeEach
    fun setUp() {
        originalLocale = Locale.getDefault()
    }

    @AfterEach
    fun tearDown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `e164 number detected regardless of region`() {
        Locale.setDefault(Locale.ITALY)
        val result = detector.detect("Call +16502530000 for support", DetectionContext.EMPTY)

        assertEquals(1, result.size)
        assertEquals(PiiCategory.PHONE_NUMBERS, result[0].category)
    }

    @Test
    fun `local format detected with matching default region`() {
        Locale.setDefault(Locale.US)
        val result = detector.detect("Call (650) 253-0000 for support", DetectionContext.EMPTY)

        assertEquals(1, result.size)
    }

    @Test
    fun `short number not detected`() {
        Locale.setDefault(Locale.US)
        val result = detector.detect("code 12345", DetectionContext.EMPTY)

        assertTrue(result.isEmpty())
    }
}
