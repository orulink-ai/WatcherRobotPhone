package com.danielealbano.androidremotecontrolmcp.privacy.ner

import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection

/** One text to analyze: [context] is the constructed prefix (may be empty), [text] the value. */
data class NerSegment(
    val key: String,
    val context: String,
    val text: String,
)

/** Model detections for one segment; detection offsets are relative to the segment's [NerSegment.text]. */
data class NerResult(
    val key: String,
    val detections: List<PiiDetection>,
)

/** On-device PII NER model. Throws [PrivacyModelException] on any model failure (fail-closed in the pipeline). */
interface PiiModelInference {
    suspend fun infer(segments: List<NerSegment>): List<NerResult>
}

/** Raised on any model load / inference failure so the pipeline can fail closed. */
class PrivacyModelException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
