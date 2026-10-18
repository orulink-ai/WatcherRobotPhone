package com.danielealbano.androidremotecontrolmcp.privacy.detectors

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import javax.inject.Inject

/**
 * IBAN detector: flags candidate IBANs (2 letters + 2 check digits + 11..30 alphanumerics) that
 * pass the ISO 7064 mod-97-10 checksum.
 */
class IbanDetector
    @Inject
    constructor() : DeterministicDetector {
        override fun detect(
            text: String,
            context: DetectionContext,
        ): List<PiiDetection> {
            val detections = mutableListOf<PiiDetection>()
            for (match in IBAN_CANDIDATE.findAll(text)) {
                if (mod97Valid(match.value)) {
                    detections +=
                        PiiDetection(
                            PiiCategory.CARDS_AND_IBAN,
                            match.range.first,
                            match.range.last + 1,
                            PiiDetection.Source.DETERMINISTIC,
                        )
                }
            }
            return detections
        }

        private fun mod97Valid(iban: String): Boolean {
            val s = iban.uppercase()
            val validChars = s.all { it in '0'..'9' || it in 'A'..'Z' }
            if (s.length < MIN_IBAN_LEN || s.length > MAX_IBAN_LEN || !validChars) return false
            val rearranged = s.substring(COUNTRY_CHECK_LEN) + s.substring(0, COUNTRY_CHECK_LEN)
            var remainder = 0
            for (ch in rearranged) {
                val value = if (ch in '0'..'9') ch - '0' else ch - 'A' + LETTER_OFFSET
                remainder =
                    if (value >= LETTER_OFFSET) {
                        (remainder * TWO_DIGIT_SHIFT + value) % MOD_97
                    } else {
                        (remainder * ONE_DIGIT_SHIFT + value) % MOD_97
                    }
            }
            return remainder == 1
        }

        companion object {
            private const val MIN_IBAN_LEN = 15
            private const val MAX_IBAN_LEN = 34
            private const val COUNTRY_CHECK_LEN = 4
            private const val LETTER_OFFSET = 10
            private const val ONE_DIGIT_SHIFT = 10
            private const val TWO_DIGIT_SHIFT = 100
            private const val MOD_97 = 97
            private val IBAN_CANDIDATE = Regex("""\b[A-Z]{2}\d{2}[A-Za-z0-9]{11,30}\b""")
        }
    }
