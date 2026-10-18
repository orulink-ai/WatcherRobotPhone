package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.data.model.PrivacyModeConfig
import com.danielealbano.androidremotecontrolmcp.data.model.RedactionMode
import javax.inject.Inject

/** Renders detections into redacted text: stable pseudonym placeholders, or `[REDACTED:<TOKEN>]`. */
class Redactor
    @Inject
    constructor(
        private val pseudonymStore: PseudonymStore,
    ) {
        /**
         * Applies [detections] (already category-filtered, non-overlapping) to [text] right-to-left so
         * earlier offsets stay valid. PSEUDONYMIZE replaces each span with a stable placeholder from the
         * [PseudonymStore]; REDACT replaces it with `[REDACTED:<TOKEN>]`.
         */
        fun apply(
            text: String,
            detections: List<PiiDetection>,
            config: PrivacyModeConfig,
        ): String {
            if (detections.isEmpty()) return text
            var result = text
            for (detection in detections.sortedByDescending { it.start }) {
                val original = text.substring(detection.start, detection.end)
                val replacement =
                    when (config.redactionMode) {
                        RedactionMode.PSEUDONYMIZE -> {
                            pseudonymStore.placeholderFor(original, detection.category, config.placeholderFormat)
                        }

                        RedactionMode.REDACT -> {
                            "[REDACTED:${detection.category.placeholderToken}]"
                        }
                    }
                result = result.substring(0, detection.start) + replacement + result.substring(detection.end)
            }
            return result
        }
    }
