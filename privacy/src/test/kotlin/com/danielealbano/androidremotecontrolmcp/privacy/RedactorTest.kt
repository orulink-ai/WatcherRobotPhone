package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.data.model.RedactionMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Redactor")
class RedactorTest {
    private val redactor = Redactor(PseudonymStore())

    private fun detection(
        category: PiiCategory,
        start: Int,
        end: Int,
    ) = PiiDetection(category, start, end, PiiDetection.Source.DETERMINISTIC)

    @Test
    fun `right-to-left replacement preserves offsets for multiple detections`() {
        val text = "email a@b.com and card 4111111111111111"
        val detections =
            listOf(
                detection(PiiCategory.EMAILS, 6, 13), // a@b.com
                detection(PiiCategory.CARDS_AND_IBAN, 23, 39), // card number
            )
        val config = PrivacyModeConfig(enabled = true, placeholderFormat = PlaceholderFormat.NUMBERED)

        val result = redactor.apply(text, detections, config)

        assertEquals("email [EMAIL_1] and card [CARD_1]", result)
    }

    @Test
    fun `redact mode renders redacted marker`() {
        val text = "card 4111111111111111"
        val config = PrivacyModeConfig(enabled = true, redactionMode = RedactionMode.REDACT)

        val result = redactor.apply(text, listOf(detection(PiiCategory.CARDS_AND_IBAN, 5, 21)), config)

        assertEquals("card [REDACTED:CARD]", result)
    }

    @Test
    fun `empty detections returns text unchanged`() {
        val config = PrivacyModeConfig(enabled = true)
        assertEquals("nothing here", redactor.apply("nothing here", emptyList(), config))
    }

    @Test
    fun `mixed categories hashed`() {
        val text = "John lives in Berlin"
        val detections =
            listOf(detection(PiiCategory.NAMES, 0, 4), detection(PiiCategory.ADDRESSES, 14, 20))
        val config = PrivacyModeConfig(enabled = true, placeholderFormat = PlaceholderFormat.HASHED)

        val result = redactor.apply(text, detections, config)

        assertTrue(result.contains("NAME#"))
        assertTrue(result.contains("ADDRESS#"))
    }
}
