package com.danielealbano.androidremotecontrolmcp.privacy.detectors

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("IbanDetector")
class IbanDetectorTest {
    private val detector = IbanDetector()

    @Test
    fun `valid IT DE GB ibans detected`() {
        listOf(
            "IT60X0542811101000000123456",
            "DE89370400440532013000",
            "GB29NWBK60161331926819",
        ).forEach { iban ->
            val result = detector.detect(iban, DetectionContext.EMPTY)
            assertEquals(1, result.size, "expected $iban to be detected")
            assertEquals(PiiCategory.CARDS_AND_IBAN, result[0].category)
        }
    }

    @Test
    fun `checksum broken iban rejected`() {
        val result = detector.detect("DE89370400440532013001", DetectionContext.EMPTY)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `iban embedded in sentence detected`() {
        val text = "Please wire to DE89370400440532013000 today"
        val result = detector.detect(text, DetectionContext.EMPTY)

        assertEquals(1, result.size)
        assertEquals("DE89370400440532013000", text.substring(result[0].start, result[0].end))
    }
}
