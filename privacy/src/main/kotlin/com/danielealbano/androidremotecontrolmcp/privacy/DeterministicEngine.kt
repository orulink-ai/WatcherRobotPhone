package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.privacy.detectors.CardDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.CredentialDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.DeterministicDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.EmailDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.IbanDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.NationalIdDetector
import com.danielealbano.androidremotecontrolmcp.privacy.detectors.PhoneDetector
import javax.inject.Inject

/**
 * Runs all deterministic detectors over a piece of text and merges overlapping spans by priority.
 */
class DeterministicEngine
    @Inject
    constructor(
        credentialDetector: CredentialDetector,
        cardDetector: CardDetector,
        ibanDetector: IbanDetector,
        emailDetector: EmailDetector,
        phoneDetector: PhoneDetector,
        nationalIdDetector: NationalIdDetector,
    ) {
        private val detectors: List<DeterministicDetector> =
            listOf(
                credentialDetector,
                cardDetector,
                ibanDetector,
                emailDetector,
                phoneDetector,
                nationalIdDetector,
            )

        fun detect(
            text: String,
            context: DetectionContext,
        ): List<PiiDetection> = mergeOverlaps(detectAll(text, context))

        /**
         * RAW (un-merged) detections from every detector. The caller MUST filter by enabled category
         * BEFORE [mergeOverlaps], otherwise a disabled category's span could suppress an overlapping
         * still-enabled span and leave that region redacted by nobody.
         */
        fun detectAll(
            text: String,
            context: DetectionContext,
        ): List<PiiDetection> = detectors.flatMap { it.detect(text, context) }

        companion object {
            /**
             * Sort by priority STRUCTURAL > DETERMINISTIC > MODEL, then by longer span, then earlier
             * start; keep a detection only if it does not overlap an already-kept (higher-priority) span.
             * Exposed for reuse by the pipeline when merging model detections with deterministic ones.
             */
            fun mergeOverlaps(detections: List<PiiDetection>): List<PiiDetection> {
                if (detections.isEmpty()) return emptyList()
                val ordered =
                    detections.sortedWith(
                        compareByDescending<PiiDetection> { priority(it.source) }
                            .thenByDescending { it.end - it.start }
                            .thenBy { it.start },
                    )
                val kept = mutableListOf<PiiDetection>()
                for (detection in ordered) {
                    val overlaps = kept.any { detection.start < it.end && it.start < detection.end }
                    if (!overlaps) kept += detection
                }
                return kept.sortedBy { it.start }
            }

            private fun priority(source: PiiDetection.Source): Int =
                when (source) {
                    PiiDetection.Source.STRUCTURAL -> PRIORITY_STRUCTURAL
                    PiiDetection.Source.DETERMINISTIC -> PRIORITY_DETERMINISTIC
                    PiiDetection.Source.MODEL -> PRIORITY_MODEL
                }

            private const val PRIORITY_STRUCTURAL = 3
            private const val PRIORITY_DETERMINISTIC = 2
            private const val PRIORITY_MODEL = 1
        }
    }
