package com.danielealbano.androidremotecontrolmcp.privacy.ner

import com.danielealbano.androidremotecontrolmcp.privacy.PiiDetection
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bounded LRU cache of model detections keyed by `SHA-256(context + U+0000 + text)`. Stores RAW
 * detections (before category filtering) so it stays valid across category-toggle changes. Screens are
 * re-dumped constantly with identical text, so caching avoids re-running inference on unchanged content.
 */
@Singleton
class NerCache
    @Inject
    constructor() {
        // accessOrder=true makes this an LRU: the least-recently-accessed key is the first in iteration
        // order, so eviction in put() drops it once the bound is exceeded.
        private val map = LinkedHashMap<String, List<PiiDetection>>(INITIAL_CAPACITY, LOAD_FACTOR, true)

        @Synchronized
        fun get(key: String): List<PiiDetection>? = map[key]

        @Synchronized
        fun put(
            key: String,
            value: List<PiiDetection>,
        ) {
            map[key] = value
            if (map.size > MAX_ENTRIES) {
                map.remove(map.keys.iterator().next())
            }
        }

        @Synchronized
        fun clear() = map.clear()

        fun keyFor(
            context: String,
            text: String,
        ): String {
            val raw = context + SEPARATOR + text
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
        }

        companion object {
            const val MAX_ENTRIES = 2048
            private const val INITIAL_CAPACITY = 16
            private const val LOAD_FACTOR = 0.75f
            private const val BYTE_MASK = 0xFF

            /** NUL separator so `("a", "bc")` and `("ab", "c")` never collide. */
            private const val SEPARATOR = "\u0000"
        }
    }
