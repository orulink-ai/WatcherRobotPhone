package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin entry point tools call to redact device-derived text before returning it. Delegates to the
 * [PrivacyPipeline] (which fails closed and passes through when Privacy Mode is disabled). [texts]
 * redacts many fields in a single batched model pass.
 */
@Singleton
class PrivacyToolGate
    @Inject
    constructor(
        private val pipeline: PrivacyPipeline,
    ) {
        suspend fun text(
            text: String?,
            fieldName: String,
        ): String? = text?.let { pipeline.processText(it, DetectionContext.forField(fieldName)) }

        /** Redacts each `(text, fieldName)` pair in one batch; null texts stay null and keep their slot. */
        suspend fun texts(items: List<Pair<String?, String>>): List<String?> {
            val presentIndices = items.indices.filter { items[it].first != null }
            if (presentIndices.isEmpty()) return items.map { null }
            val processed =
                pipeline.processTexts(
                    presentIndices.map { TextItem(items[it].first!!, DetectionContext.forField(items[it].second)) },
                )
            val result = arrayOfNulls<String>(items.size)
            presentIndices.forEachIndexed { batchIndex, itemIndex -> result[itemIndex] = processed[batchIndex] }
            return result.toList()
        }

        suspend fun tree(result: MultiWindowResult): ProcessedTree = pipeline.processTree(result)
    }
