package com.danielealbano.androidremotecontrolmcp.privacy.detectors

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import javax.inject.Inject

/**
 * Structural credential detector: flags the whole text as a credential when the field is a password
 * field, or when it is an editable field whose context contains a credential keyword.
 */
class CredentialDetector
    @Inject
    constructor() : DeterministicDetector {
        override fun detect(
            text: String,
            context: DetectionContext,
        ): List<PiiDetection> {
            if (text.isBlank()) return emptyList()
            val flagged =
                context.isPassword ||
                    (context.isEditable && ContextKeywords.matches(context, ContextKeywords.CREDENTIAL))
            return if (flagged) {
                listOf(PiiDetection(PiiCategory.CREDENTIALS, 0, text.length, PiiDetection.Source.STRUCTURAL))
            } else {
                emptyList()
            }
        }
    }
