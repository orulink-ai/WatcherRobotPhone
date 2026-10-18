package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ServerLogSegmentedStore")
class ServerLogSegmentedStoreTest {
    @TempDir
    lateinit var tempDir: File

    private fun store(
        maxEntriesPerSegment: Int = ServerLogSegmentedStore.MAX_ENTRIES_PER_SEGMENT,
        maxSegments: Int = ServerLogSegmentedStore.MAX_SEGMENTS,
    ) = ServerLogSegmentedStore(File(tempDir, "logs"), maxEntriesPerSegment, maxSegments)

    @Test
    fun `append then readIndex and readEntry roundtrip`() =
        runTest {
            val store = store()
            store.append(100L, ServerLogEntry.Type.SERVER, "started", null, null)
            store.append(200L, ServerLogEntry.Type.TOOL_CALL, "", "tap", 42L)
            store.append(300L, ServerLogEntry.Type.TUNNEL, "connected", null, null)

            val index = store.readIndex()
            assertEquals(3, index.size)
            assertEquals(listOf(100L, 200L, 300L), index.map { it.timestamp })

            val second = store.readEntry(index[1])
            assertEquals(ServerLogEntry.Type.TOOL_CALL, second.type)
            assertEquals("tap", second.toolName)
            assertEquals("", second.message)
            assertEquals(42L, second.durationMs)
        }

    @Test
    fun `null duration and null toolName roundtrip`() =
        runTest {
            val store = store()
            store.append(100L, ServerLogEntry.Type.SERVER, "hello", null, null)

            val entry = store.readEntry(store.readIndex().first())
            assertNull(entry.durationMs)
            assertNull(entry.toolName)
            assertEquals("hello", entry.message)
        }

    @Test
    fun `rotation starts new segment after maxEntriesPerSegment`() =
        runTest {
            val store = store(maxEntriesPerSegment = 3, maxSegments = 2)
            repeat(4) { i -> store.append(i.toLong(), ServerLogEntry.Type.SERVER, "m$i", null, null) }

            val index = store.readIndex()
            assertEquals(4, index.size)
            assertEquals(3, index.count { it.segmentSeq == index.first().segmentSeq })
            assertTrue(index[3].segmentSeq > index[0].segmentSeq)
        }

    @Test
    fun `oldest segment pair deleted beyond maxSegments`() =
        runTest {
            val store = store(maxEntriesPerSegment = 3, maxSegments = 2)
            repeat(7) { i -> store.append(i.toLong(), ServerLogEntry.Type.SERVER, "m$i", null, null) }

            val index = store.readIndex()
            assertEquals(4, index.size)
            assertEquals(
                listOf("m3", "m4", "m5", "m6"),
                index.map { store.readEntry(it).message },
            )
        }

    @Test
    fun `clear removes all entries and never reuses sequence numbers`() =
        runTest {
            val store = store()
            store.append(1L, ServerLogEntry.Type.SERVER, "a", null, null)
            val seqBefore = store.readIndex().first().segmentSeq

            store.clear()
            assertTrue(store.readIndex().isEmpty())

            store.append(2L, ServerLogEntry.Type.SERVER, "b", null, null)
            assertTrue(store.readIndex().first().segmentSeq > seqBefore)
        }

    @Test
    fun `message truncated to the 500-byte entry budget at utf8 boundary`() =
        runTest {
            val store = store()
            store.append(1L, ServerLogEntry.Type.SERVER, "€".repeat(300), null, null)

            val ref = store.readIndex().first()
            assertTrue(ref.messageLen <= ServerLogSegmentedStore.MAX_ENTRY_DATA_BYTES)
            val message = store.readEntry(ref).message
            assertFalse(message.contains('�'))
            assertTrue(message.toByteArray(Charsets.UTF_8).size <= ServerLogSegmentedStore.MAX_ENTRY_DATA_BYTES)
        }

    @Test
    fun `message budget shrinks by the tool name bytes`() =
        runTest {
            val store = store()
            store.append(1L, ServerLogEntry.Type.TOOL_CALL, "€".repeat(300), "a".repeat(100), 5L)

            val ref = store.readIndex().first()
            assertEquals(ServerLogSegmentedStore.MAX_TOOL_NAME_BYTES, ref.toolNameLen)
            val messageBudget =
                ServerLogSegmentedStore.MAX_ENTRY_DATA_BYTES - ServerLogSegmentedStore.MAX_TOOL_NAME_BYTES
            assertTrue(ref.messageLen <= messageBudget)
            assertTrue(ref.toolNameLen + ref.messageLen <= ServerLogSegmentedStore.MAX_ENTRY_DATA_BYTES)
        }

    @Test
    fun `toolName truncated to MAX_TOOL_NAME_BYTES`() =
        runTest {
            val store = store()
            store.append(1L, ServerLogEntry.Type.TOOL_CALL, "short", "b".repeat(200), 5L)

            val ref = store.readIndex().first()
            assertEquals(ServerLogSegmentedStore.MAX_TOOL_NAME_BYTES, ref.toolNameLen)
            assertEquals("short", store.readEntry(ref).message)
        }

    @Test
    fun `partial tail index record ignored`() =
        runTest {
            val store = store()
            store.append(1L, ServerLogEntry.Type.SERVER, "a", null, null)
            store.append(2L, ServerLogEntry.Type.SERVER, "b", null, null)

            FileOutputStream(ServerLogSegmentFiles.indexFile(File(tempDir, "logs"), 1), true).use {
                it.write(ByteArray(10) { 0x7F })
            }

            assertEquals(2, store.readIndex().size)
        }

    @Test
    fun `unknown type id skipped`() =
        runTest {
            val store = store()
            store.append(1L, ServerLogEntry.Type.SERVER, "a", null, null)

            val garbage = ByteBuffer.allocate(ServerLogSegmentFiles.INDEX_RECORD_BYTES)
            garbage.putLong(2L)
            garbage.putInt(0)
            garbage.putInt(ServerLogSegmentFiles.NO_DURATION)
            garbage.putShort(0)
            garbage.putShort(0)
            garbage.put(99.toByte())
            FileOutputStream(ServerLogSegmentFiles.indexFile(File(tempDir, "logs"), 1), true).use {
                it.write(garbage.array())
            }

            val index = store.readIndex()
            assertEquals(1, index.size)
            assertEquals("a", store.readEntry(index.first()).message)
        }

    @Test
    fun `readEntry with missing data file returns corrupted placeholder`() =
        runTest {
            val store = store()
            store.append(1L, ServerLogEntry.Type.SERVER, "a", null, null)
            val ref = store.readIndex().first()

            ServerLogSegmentFiles.dataFile(File(tempDir, "logs"), ref.segmentSeq).delete()

            assertEquals(ServerLogSegmentedStore.CORRUPTED_ENTRY_MESSAGE, store.readEntry(ref).message)
        }

    @Test
    fun `existing segments beyond cap deleted at init`() =
        runTest {
            val dir = File(tempDir, "logs").also { it.mkdirs() }
            (1..3).forEach { seq ->
                ServerLogSegmentFiles.indexFile(dir, seq).writeBytes(ByteArray(0))
                ServerLogSegmentFiles.dataFile(dir, seq).writeBytes(ByteArray(0))
            }

            val store = store(maxEntriesPerSegment = 3, maxSegments = 2)
            store.readIndex()

            assertFalse(ServerLogSegmentFiles.indexFile(dir, 1).exists())
            assertFalse(ServerLogSegmentFiles.dataFile(dir, 1).exists())
        }

    @Test
    fun `append returns index ref and reports evicted segments`() =
        runTest {
            val store = store(maxEntriesPerSegment = 3, maxSegments = 2)
            val nonRotating = store.append(1L, ServerLogEntry.Type.SERVER, "m0", null, null)
            assertTrue(nonRotating.removedSegmentSeqs.isEmpty())

            var last = nonRotating
            (1 until 7).forEach { i ->
                last = store.append(i.toLong(), ServerLogEntry.Type.SERVER, "m$i", null, null)
            }

            assertEquals(listOf(1), last.removedSegmentSeqs)
            assertEquals(store.readIndex().last(), last.entry)
        }
}
