package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * Builds [DetectionContext]s from accessibility nodes, including a geometric nearest-label fallback
 * for editable fields that lack a first-class [AccessibilityNodeData.labeledByText] association.
 */
class ContextExtractor
    @Inject
    constructor() {
        /**
         * Builds the [DetectionContext] for [node]. [nearestLabel] is the geometric fallback: the text
         * of the closest non-editable text node above or left of [node] (same window), used only when
         * [AccessibilityNodeData.labeledByText] is null.
         */
        fun extract(
            node: AccessibilityNodeData,
            nearestLabel: String?,
        ): DetectionContext =
            DetectionContext(
                resourceIdWords = splitResourceId(node.resourceId),
                hintText = node.hintText,
                labelText = node.labeledByText ?: nearestLabel,
                contentDescription = node.contentDescription,
                isPassword = node.isPassword,
                isEditable = node.editable,
            )

        /** "com.app:id/card_number_field" -> ["card","number","field"] (strip package, split _ - and camelCase). */
        fun splitResourceId(resourceId: String?): List<String> {
            if (resourceId.isNullOrBlank()) return emptyList()
            val local = resourceId.substringAfterLast('/').substringAfterLast(':')
            return local
                .replace(CAMEL_CASE_BOUNDARY, "$1 $2")
                .split('_', '-', ' ')
                .map { it.lowercase() }
                .filter { it.isNotBlank() }
        }

        /**
         * Nearest-label pass over one window tree: for every editable node without labeledByText, pick
         * the nearest node with non-blank text, no editable flag, whose bounds are above
         * (bottom <= target.top) or left (right <= target.left) within [NEAREST_LABEL_MAX_PX], preferring
         * the smallest Euclidean distance between bounds centers. Returns nodeId -> labelText.
         */
        fun computeNearestLabels(root: AccessibilityNodeData): Map<String, String> {
            val all = mutableListOf<AccessibilityNodeData>()

            fun collect(node: AccessibilityNodeData) {
                all += node
                node.children.forEach(::collect)
            }
            collect(root)
            val labels = all.filter { !it.editable && !it.text.isNullOrBlank() }
            val result = HashMap<String, String>()
            for (target in all) {
                if (!target.editable || !target.labeledByText.isNullOrBlank()) continue
                val nearest = nearestLabelFor(target, labels)
                nearest?.text?.let { result[target.id] = it }
            }
            return result
        }

        private fun nearestLabelFor(
            target: AccessibilityNodeData,
            labels: List<AccessibilityNodeData>,
        ): AccessibilityNodeData? {
            val tb = target.bounds
            var best: AccessibilityNodeData? = null
            var bestDist = Double.MAX_VALUE
            for (label in labels) {
                val lb = label.bounds
                val isAbove = lb.bottom <= tb.top
                val isLeft = lb.right <= tb.left
                if (!isAbove && !isLeft) continue
                val dx = ((lb.left + lb.right) / 2.0) - ((tb.left + tb.right) / 2.0)
                val dy = ((lb.top + lb.bottom) / 2.0) - ((tb.top + tb.bottom) / 2.0)
                val dist = sqrt(dx * dx + dy * dy)
                if (dist <= NEAREST_LABEL_MAX_PX && dist < bestDist) {
                    bestDist = dist
                    best = label
                }
            }
            return best
        }

        companion object {
            private const val NEAREST_LABEL_MAX_PX = 300.0
            private val CAMEL_CASE_BOUNDARY = Regex("([a-z0-9])([A-Z])")
        }
    }
