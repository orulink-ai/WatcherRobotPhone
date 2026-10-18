package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.privacy.detectors.CardDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.CredentialDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.EmailDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.IbanDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.NationalIdDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.PhoneDetector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DeterministicEngine")
class DeterministicEngineTest {
    private val engine =
        DeterministicEngine(
            CredentialDetector(),
            CardDetector(),
            IbanDetector(),
            EmailDetector(),
            PhoneDetector(),
            NationalIdDetector(),
        )

    @Test
    fun `multi-detector text yields merged non-overlapping spans`() {
        val text = "me@x.com 4111 1111 1111 1111"
        val result = engine.detect(text, DetectionContext.EMPTY)

        assertEquals(2, result.size)
        assertTrue(result[0].start < result[1].start)
        // Non-overlapping.
        assertTrue(result[0].end <= result[1].start)
        assertEquals(setOf(PiiCategory.EMAILS, PiiCategory.CARDS_AND_IBAN), result.map { it.category }.toSet())
    }

    @Test
    fun `structural wins overlap`() {
        val text = "secret me@x.com"
        val result = engine.detect(text, DetectionContext(isPassword = true))

        assertEquals(1, result.size)
        assertEquals(PiiCategory.CREDENTIALS, result[0].category)
        assertEquals(PiiDetection.Source.STRUCTURAL, result[0].source)
        assertEquals(0, result[0].start)
        assertEquals(text.length, result[0].end)
    }
}
