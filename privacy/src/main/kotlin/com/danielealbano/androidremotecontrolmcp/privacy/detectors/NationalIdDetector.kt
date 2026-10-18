package com.danielealbano.androidremotecontrolmcp.privacy.detectors

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import javax.inject.Inject

/**
 * National-ID / document-number detector. Only active when the context contains a national-ID cue
 * (SSN, tax id, passport, driver licence, …). Flags alphanumeric runs (single space/dash/dot
 * separators allowed) whose alphanumeric length is 5..20 and which contain at least 3 digits.
 */
class NationalIdDetector
    @Inject
    constructor() : DeterministicDetector {
        override fun detect(
            text: String,
            context: DetectionContext,
        ): List<PiiDetection> {
            if (!ContextKeywords.matches(context, ContextKeywords.NATIONAL_ID)) return emptyList()
            val detections = mutableListOf<PiiDetection>()
            for (match in ALNUM_RUN.findAll(text)) {
                val alnum = match.value.count { it.isLetterOrDigit() }
                val digitCount = match.value.count { it.isDigit() }
                if (alnum in MIN_ID_LEN..MAX_ID_LEN && digitCount >= MIN_DIGITS) {
                    detections +=
                        PiiDetection(
                            PiiCategory.NATIONAL_IDS,
                            match.range.first,
                            match.range.last + 1,
                            PiiDetection.Source.DETERMINISTIC,
                        )
                }
            }
            return detections
        }

        companion object {
            private const val MIN_ID_LEN = 5
            private const val MAX_ID_LEN = 20
            private const val MIN_DIGITS = 3
            private val ALNUM_RUN = Regex("""[A-Za-z0-9](?:[ .\-]?[A-Za-z0-9])*""")
        }
    }
