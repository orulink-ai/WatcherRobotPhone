package com.danielealbano.androidremotecontrolmcp.services.sharing

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("EphemeralFileLinkServiceImpl")
class EphemeralFileLinkServiceImplTest {
    private fun newService(): EphemeralFileLinkServiceImpl = EphemeralFileLinkServiceImpl()

    @Test
    @DisplayName("register returns a 64-hex token held in memory, and resolve finds it")
    fun registerAndResolve() =
        runTest {
            val service = newService()
            val bytes = byteArrayOf(1, 2, 3)
            val token = service.register(bytes, "application/octet-stream", "a.bin")

            assertTrue(token.matches(Regex("[0-9a-f]{64}")), "token must be 64-hex")
            assertEquals("/s/$token", service.pathFor(token))

            val entry = service.resolve(token)
            assertNotNull(entry)
            assertEquals("application/octet-stream", entry!!.mimeType)
            assertEquals("a.bin", entry.fileName)
            assertArrayEquals(byteArrayOf(1, 2, 3), entry.bytes)
        }

    @Test
    @DisplayName("resolve returns null for an unknown token")
    fun resolveUnknown() =
        runTest {
            assertNull(newService().resolve("deadbeef"))
        }

    @Test
    @DisplayName("resolve returns null after the TTL has elapsed")
    fun resolveExpired() =
        runTest {
            val service = newService()
            var now = 1_000L
            service.clockMs = { now }
            val token = service.register(byteArrayOf(1), "text/plain", "b.bin")

            now += EphemeralFileLinkService.TTL_MS + 1
            assertNull(service.resolve(token), "expired link must not resolve")
        }

    @Test
    @DisplayName("adding the 21st link evicts the oldest and caps at 20")
    fun evictsOldest() =
        runTest {
            val service = newService()
            val firstToken = service.register(byteArrayOf(0), "text/plain", "first.bin")
            repeat(EphemeralFileLinkService.MAX_LINKS) { i ->
                service.register(byteArrayOf(i.toByte()), "text/plain", "f$i.bin")
            }

            assertNull(service.resolve(firstToken), "oldest link evicted")
        }

    @Test
    @DisplayName("rejects a single blob larger than the in-memory budget")
    fun rejectsBlobLargerThanBudget() =
        runTest {
            val service = newService()
            val oversized = ByteArray((EphemeralFileLinkService.MAX_TOTAL_BYTES + 1).toInt())
            assertThrows<IllegalArgumentException> {
                service.register(oversized, "application/octet-stream", "big.bin")
            }
        }

    @Test
    @DisplayName("evicts oldest links to honor the total byte budget")
    fun evictsToHonorByteBudget() =
        runTest {
            val service = newService()
            // Two 40MB blobs sum to 80MB > 64MB budget, so registering the second evicts the first.
            val fortyMb = (40L * 1024 * 1024).toInt()
            val firstToken = service.register(ByteArray(fortyMb), "application/octet-stream", "a.bin")
            val secondToken = service.register(ByteArray(fortyMb), "application/octet-stream", "b.bin")

            assertNull(service.resolve(firstToken), "oldest link evicted to honor the byte budget")
            assertNotNull(service.resolve(secondToken), "newest link must remain")
        }
}
