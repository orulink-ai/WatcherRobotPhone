package com.danielealbano.androidremotecontrolmcp.services.sharing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory implementation of [EphemeralFileLinkService].
 *
 * All state — including the blob bytes — lives in a [Mutex]-guarded insertion-ordered map in RAM.
 * Nothing is written to disk and nothing is persisted across process death. Tokens/URLs are never logged.
 */
@Singleton
class EphemeralFileLinkServiceImpl
    @Inject
    constructor() : EphemeralFileLinkService {
        /** Test seam for the clock; defaults to the wall clock. */
        internal var clockMs: () -> Long = { System.currentTimeMillis() }

        private val mutex = Mutex()
        private val entries = LinkedHashMap<String, LinkEntry>()

        override suspend fun register(
            bytes: ByteArray,
            mimeType: String,
            fileName: String,
        ): String =
            mutex.withLock {
                require(bytes.size <= EphemeralFileLinkService.MAX_TOTAL_BYTES) {
                    "Blob (${bytes.size} bytes) exceeds the in-memory link budget of " +
                        "${EphemeralFileLinkService.MAX_TOTAL_BYTES} bytes."
                }
                purgeExpiredLocked()
                val token = CapabilityToken.generate()
                // Evict oldest (FIFO) until the new blob fits within both the count and byte budgets.
                // Terminates: the new blob alone is ≤ MAX_TOTAL_BYTES, so evicting to empty always admits it.
                while (entries.isNotEmpty() &&
                    (
                        entries.size >= EphemeralFileLinkService.MAX_LINKS ||
                            currentTotalBytesLocked() + bytes.size > EphemeralFileLinkService.MAX_TOTAL_BYTES
                    )
                ) {
                    entries.remove(
                        entries.entries
                            .iterator()
                            .next()
                            .key,
                    )
                }
                entries[token] =
                    LinkEntry(
                        token = token,
                        bytes = bytes,
                        mimeType = mimeType,
                        fileName = fileName,
                        expiresAtMs = clockMs() + EphemeralFileLinkService.TTL_MS,
                    )
                token
            }

        override suspend fun resolve(token: String): LinkEntry? =
            mutex.withLock {
                // purgeExpiredLocked() already dropped every expired entry, so any survivor is live.
                purgeExpiredLocked()
                entries[token]
            }

        override suspend fun purgeExpired() = mutex.withLock { purgeExpiredLocked() }

        override fun pathFor(token: String): String = EphemeralFileLinkService.PATH_PREFIX + token

        private fun currentTotalBytesLocked(): Long = entries.values.sumOf { it.bytes.size.toLong() }

        private fun purgeExpiredLocked() {
            val now = clockMs()
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                if (now >= iterator.next().value.expiresAtMs) {
                    iterator.remove()
                }
            }
        }
    }
