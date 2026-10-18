package com.danielealbano.androidremotecontrolmcp.privacy.ner

import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caching front for [PiiModelInference]: returns cached detections for segments seen before and only
 * runs inference on the rest, then populates the cache. Returns a map of segment key → raw detections.
 */
@Singleton
class NerEngine
    @Inject
    constructor(
        private val inference: PiiModelInference,
        private val cache: NerCache,
    ) {
        suspend fun detect(segments: List<NerSegment>): Map<String, List<PiiDetection>> {
            val result = HashMap<String, List<PiiDetection>>()
            val uncached = mutableListOf<NerSegment>()
            for (segment in segments) {
                val cached = cache.get(cache.keyFor(segment.context, segment.text))
                if (cached != null) {
                    result[segment.key] = cached
                } else {
                    uncached += segment
                }
            }
            if (uncached.isNotEmpty()) {
                val inferred = inference.infer(uncached).associateBy { it.key }
                for (segment in uncached) {
                    val detections = inferred[segment.key]?.detections.orEmpty()
                    result[segment.key] = detections
                    cache.put(cache.keyFor(segment.context, segment.text), detections)
                }
            }
            return result
        }
    }
