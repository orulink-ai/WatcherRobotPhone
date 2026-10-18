package com.danielealbano.androidremotecontrolmcp.privacy

import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import java.math.BigInteger
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session-scoped, in-memory bidirectional value<->placeholder map for pseudonymization. Backed by a
 * bounded LRU (default 50 000 entries): the reverse map (placeholder -> value) is the LRU authority,
 * and evicting an entry also removes its paired forward entry so the two directions never dangle. Both
 * [placeholderFor] and [resolve] count as access, keeping active placeholders hot. Never persisted;
 * cleared only by [clear] on service destroy.
 */
@Singleton
class PseudonymStore
    @Inject
    constructor() {
        private var maxEntries: Int = MAX_ENTRIES

        internal constructor(testMaxEntries: Int) : this() {
            maxEntries = testMaxEntries
        }

        private data class Entry(
            val value: String,
            val category: PiiCategory,
        )

        private val forward = HashMap<Pair<PiiCategory, String>, String>()
        private val counters = HashMap<PiiCategory, Int>()
        private val reverse =
            object : LinkedHashMap<String, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean {
                    if (size > maxEntries) {
                        forward.remove(eldest.value.category to eldest.value.value)
                        return true
                    }
                    return false
                }
            }

        @Synchronized
        fun placeholderFor(
            value: String,
            category: PiiCategory,
            format: PlaceholderFormat,
        ): String {
            val key = category to value
            forward[key]?.let { existing ->
                reverse[existing] // mark most-recently-used
                return existing
            }
            val placeholder =
                when (format) {
                    PlaceholderFormat.HASHED -> "${category.placeholderToken}#${hash5(value, category)}"
                    PlaceholderFormat.NUMBERED -> "[${category.placeholderToken}_${nextNumber(category)}]"
                }
            forward[key] = placeholder
            reverse[placeholder] = Entry(value, category)
            return placeholder
        }

        @Synchronized
        fun resolve(placeholder: String): String? = reverse[placeholder]?.value

        @Synchronized
        fun clear() {
            forward.clear()
            reverse.clear()
            counters.clear()
        }

        private fun nextNumber(category: PiiCategory): Int {
            val next = (counters[category] ?: 0) + 1
            counters[category] = next
            return next
        }

        private fun hash5(
            value: String,
            category: PiiCategory,
        ): String {
            val input = "$value|${category.name}".toByteArray(Charsets.UTF_8)
            val digest = MessageDigest.getInstance("SHA-256").digest(input)
            return BigInteger(1, digest).toString(BASE36).take(HASH_LENGTH)
        }

        companion object {
            const val MAX_ENTRIES = 50_000
            private const val INITIAL_CAPACITY = 16
            private const val LOAD_FACTOR = 0.75f
            private const val BASE36 = 36
            private const val HASH_LENGTH = 5

            internal val PLACEHOLDER_PATTERN =
                Regex(
                    "\\[(CREDENTIAL|CARD|EMAIL|PHONE|NAME|ADDRESS|ID)_\\d+]|" +
                        "(CREDENTIAL|CARD|EMAIL|PHONE|NAME|ADDRESS|ID)#[a-z0-9]{5}",
                )
        }
    }
