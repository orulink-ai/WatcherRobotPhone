package com.danielealbano.androidremotecontrolmcp.privacy.detectors

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import javax.inject.Inject

/**
 * Payment-card detector: flags maximal digit runs (single space/dash separators allowed) of 12..19
 * digits that pass the Luhn checksum. Suppressed when the context indicates a non-card number
 * (tracking/order/etc.) without a positive card cue.
 */
class CardDetector
    @Inject
    constructor() : DeterministicDetector {
        override fun detect(
            text: String,
            context: DetectionContext,
        ): List<PiiDetection> {
            if (ContextKeywords.matches(context, ContextKeywords.CARD_NEGATIVE) &&
                !ContextKeywords.matches(context, ContextKeywords.CARD_POSITIVE)
            ) {
                return emptyList()
            }
            val detections = mutableListOf<PiiDetection>()
            for (match in DIGIT_RUN.findAll(text)) {
                val digits = match.value.filter { it.isDigit() }
                if (luhnValid(digits)) {
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

        private fun luhnValid(digits: String): Boolean {
            if (digits.length !in MIN_CARD_DIGITS..MAX_CARD_DIGITS) return false
            var sum = 0
            var alt = false
            for (i in digits.indices.reversed()) {
                var d = digits[i] - '0'
                if (alt) {
                    d *= 2
                    if (d > MAX_SINGLE_DIGIT) d -= MAX_SINGLE_DIGIT
                }
                sum += d
                alt = !alt
            }
            return sum % LUHN_MODULUS == 0
        }

        companion object {
            private const val MIN_CARD_DIGITS = 12
            private const val MAX_CARD_DIGITS = 19
            private const val MAX_SINGLE_DIGIT = 9
            private const val LUHN_MODULUS = 10
            private val DIGIT_RUN = Regex("""\d(?:[- ]?\d)*""")
        }
    }
