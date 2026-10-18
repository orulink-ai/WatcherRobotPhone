package com.danielealbano.androidremotecontrolmcp.services.sharing

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SharedContentInboxImpl")
class SharedContentInboxImplTest {
    private fun newInbox(): SharedContentInboxImpl = SharedContentInboxImpl()

    private fun textItem(
        id: String,
        text: String = id,
        expiresAtMs: Long = Long.MAX_VALUE,
    ): SharedItem =
        SharedItem(
            id = id,
            kind = SharedItem.Kind.TEXT,
            mimeType = "text/plain",
            fileName = null,
            text = text,
            bytes = null,
            sizeBytes = text.toByteArray().size.toLong(),
            createdAtMs = 0L,
            expiresAtMs = expiresAtMs,
        )

    /** Builds a BLOB item with a logical [sizeBytes] for accounting (the byte array itself is a small placeholder). */
    private fun blobItem(
        id: String,
        sizeBytes: Long,
        expiresAtMs: Long = Long.MAX_VALUE,
    ): SharedItem =
        SharedItem(
            id = id,
            kind = SharedItem.Kind.BLOB,
            mimeType = "application/octet-stream",
            fileName = id,
            text = null,
            bytes = byteArrayOf(0),
            sizeBytes = sizeBytes,
            createdAtMs = 0L,
            expiresAtMs = expiresAtMs,
        )

    @Test
    @DisplayName("add then drainAll returns items in order and clears the inbox")
    fun addThenDrainAllReturnsAndClears() =
        runTest {
            val inbox = newInbox()
            assertTrue(inbox.add(textItem("a")))
            assertTrue(inbox.add(textItem("b")))

            val drained = inbox.drainAll()
            assertEquals(listOf("a", "b"), drained.map { it.id })
            assertTrue(inbox.drainAll().isEmpty(), "second drain must be empty (consume-on-read)")
        }

    @Test
    @DisplayName("rejects a blob larger than 10MB")
    fun rejectsFileOver10Mb() =
        runTest {
            val inbox = newInbox()
            assertFalse(inbox.add(blobItem("big", SharedContentInbox.MAX_FILE_BYTES + 1)))
            assertTrue(inbox.drainAll().isEmpty())
        }

    @Test
    @DisplayName("evicts the oldest item beyond 5")
    fun evictsOldestBeyond5Items() =
        runTest {
            val inbox = newInbox()
            assertTrue(inbox.add(blobItem("0", 1L)))
            repeat(5) { i -> assertTrue(inbox.add(blobItem("${i + 1}", 1L))) }

            val drained = inbox.drainAll()
            assertEquals(SharedContentInbox.MAX_ITEMS, drained.size)
            assertEquals(listOf("1", "2", "3", "4", "5"), drained.map { it.id })
        }

    @Test
    @DisplayName("evicts oldest to honor the 50MB total bound")
    fun evictsToHonor50MbTotal() =
        runTest {
            val inbox = newInbox()
            // Five 10MB items fill exactly 50MB; a sixth forces eviction of the eldest.
            repeat(6) { i -> assertTrue(inbox.add(blobItem("$i", SharedContentInbox.MAX_FILE_BYTES))) }

            val drained = inbox.drainAll()
            assertTrue(
                drained.sumOf { it.sizeBytes } <= SharedContentInbox.MAX_TOTAL_BYTES,
                "retained total must not exceed the 50MB bound",
            )
            assertTrue(drained.size <= SharedContentInbox.MAX_ITEMS)
            assertFalse(drained.any { it.id == "0" }, "eldest item must be evicted")
        }

    @Test
    @DisplayName("drops expired items on drain")
    fun dropsExpiredOnDrain() =
        runTest {
            val inbox = newInbox()
            inbox.clockMs = { 10_000L }
            assertTrue(inbox.add(textItem("expired", expiresAtMs = 5_000L)))
            assertTrue(inbox.add(textItem("live", expiresAtMs = 20_000L)))

            val drained = inbox.drainAll()
            assertEquals(listOf("live"), drained.map { it.id }, "expired item must be excluded")
        }
}
