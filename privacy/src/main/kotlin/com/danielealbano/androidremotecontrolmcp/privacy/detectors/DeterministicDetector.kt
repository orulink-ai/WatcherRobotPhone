package com.danielealbano.androidremotecontrolmcp.privacy.detectors

import com.danielealbano.androidremotecontrolmcp.privacy.DetectionContext
import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection

/** A single deterministic (rule/checksum-based) PII detector over a piece of [text]. */
interface DeterministicDetector {
    fun detect(
        text: String,
        context: DetectionContext,
    ): List<PiiDetection>
}
