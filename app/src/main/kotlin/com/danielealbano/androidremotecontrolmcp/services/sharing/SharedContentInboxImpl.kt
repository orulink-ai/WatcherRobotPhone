package com.danielealbano.androidremotecontrolmcp.services.sharing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory implementation of [SharedContentInbox].
 *
 * State — including blob bytes — lives in a [Mutex]-guarded insertion-ordered map in RAM; nothing is
 * written to disk or persisted.
 */
@Singleton
class SharedContentInboxImpl
    @Inject
    constructor() : SharedContentInbox {
        /** Test seam for the clock; defaults to the wall clock. */
        internal var clockMs: () -> Long = { System.currentTimeMillis() }

        private val mutex = Mutex()
        private val items = LinkedHashMap<String, SharedItem>()

        override suspend fun add(item: SharedItem): Boolean {
            if (item.sizeBytes > SharedContentInbox.MAX_FILE_BYTES) {
                return false
            }
            mutex.withLock {
                purgeExpiredLocked()
                // Terminates: a single item is ≤ 10 MB < 50 MB total, so evicting to empty always admits it.
                while (items.size >= SharedContentInbox.MAX_ITEMS ||
                    currentTotalBytesLocked() + item.sizeBytes > SharedContentInbox.MAX_TOTAL_BYTES
                ) {
                    if (items.isEmpty()) break
                    items.remove(
                        items.entries
                            .iterator()
                            .next()
                            .key,
                    )
                }
                items[item.id] = item
            }
            return true
        }

        override suspend fun drainAll(): List<SharedItem> =
            mutex.withLock {
                purgeExpiredLocked()
                val snapshot = items.values.toList()
                items.clear()
                snapshot
            }

        override suspend fun purgeExpired() = mutex.withLock { purgeExpiredLocked() }

        private fun currentTotalBytesLocked(): Long = items.values.sumOf { it.sizeBytes }

        private fun purgeExpiredLocked() {
            val now = clockMs()
            val iterator = items.entries.iterator()
            while (iterator.hasNext()) {
                if (now >= iterator.next().value.expiresAtMs) {
                    iterator.remove()
                }
            }
        }
    }
