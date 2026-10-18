package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Result of one append: the new entry's index ref plus any segment(s) evicted by rotation. */
data class AppendResult(
    val entry: ServerLogIndexEntry,
    val removedSegmentSeqs: List<Int>,
)

/**
 * Segmented on-disk circular log. Entries append to a segment pair `NNNNN.idx`/`NNNNN.data`; a
 * segment holds at most [maxEntriesPerSegment] entries and at most [maxSegments] pairs are kept
 * (oldest deleted on rotation). Index records are fixed-size
 * ([ServerLogSegmentFiles.INDEX_RECORD_BYTES]) so the index is random-access and memory-mappable;
 * tool-name/message bytes live in the data file. The active segment's append streams stay open
 * (reopened on rotation/[clear]) so an append costs no open/close syscalls. File naming and
 * record parsing live in [ServerLogSegmentFiles].
 *
 * Index record layout (big-endian, 24 bytes):
 * timestampMs Long(8) | dataOffset Int(4) | durationMs Int(4, -1 = absent) |
 * toolNameLen Short(2) | messageLen Short(2) | typeId Byte(1) | reserved(3)
 *
 * All public methods serialize on an internal [Mutex] and perform blocking I/O — callers MUST
 * invoke them on Dispatchers.IO. Segment sequence numbers are monotonic within a process (never
 * reused, including after [clear]) so (segmentSeq, slot) uniquely identifies an entry.
 */
class ServerLogSegmentedStore(
    private val directory: File,
    private val maxEntriesPerSegment: Int = MAX_ENTRIES_PER_SEGMENT,
    private val maxSegments: Int = MAX_SEGMENTS,
) {
    private val mutex = Mutex()
    private var initialized = false
    private var activeSeq = 1
    private var activeCount = 0
    private var activeDataLength = 0
    private var dataOut: FileOutputStream? = null
    private var idxOut: FileOutputStream? = null

    suspend fun append(
        timestampMs: Long,
        type: ServerLogEntry.Type,
        message: String,
        toolName: String?,
        durationMs: Long?,
    ): AppendResult =
        mutex.withLock {
            ensureInitializedLocked()
            val removedSegmentSeqs =
                if (activeCount >= maxEntriesPerSegment) rollLocked() else emptyList()
            val toolNameBytes = ServerLogSegmentFiles.truncateUtf8(toolName.orEmpty(), MAX_TOOL_NAME_BYTES)
            val messageBytes =
                ServerLogSegmentFiles.truncateUtf8(message, MAX_ENTRY_DATA_BYTES - toolNameBytes.size)
            val dataOffset = activeDataLength
            val dataStream = checkNotNull(dataOut)
            dataStream.write(toolNameBytes)
            dataStream.write(messageBytes)
            activeDataLength += toolNameBytes.size + messageBytes.size
            val storedDuration = durationMs?.coerceIn(0L, Int.MAX_VALUE.toLong())
            val record = ByteBuffer.allocate(ServerLogSegmentFiles.INDEX_RECORD_BYTES)
            record.putLong(timestampMs)
            record.putInt(dataOffset)
            record.putInt(storedDuration?.toInt() ?: ServerLogSegmentFiles.NO_DURATION)
            record.putShort(toolNameBytes.size.toShort())
            record.putShort(messageBytes.size.toShort())
            record.put(type.id)
            checkNotNull(idxOut).write(record.array())
            val slot = activeCount
            activeCount++
            AppendResult(
                entry =
                    ServerLogIndexEntry(
                        segmentSeq = activeSeq,
                        slot = slot,
                        timestamp = timestampMs,
                        type = type,
                        durationMs = storedDuration,
                        dataOffset = dataOffset,
                        toolNameLen = toolNameBytes.size,
                        messageLen = messageBytes.size,
                    ),
                removedSegmentSeqs = removedSegmentSeqs,
            )
        }

    suspend fun readIndex(): List<ServerLogIndexEntry> =
        mutex.withLock {
            ensureInitializedLocked()
            segmentSeqsLocked().flatMap { ServerLogSegmentFiles.readSegmentIndex(directory, it) }
        }

    suspend fun readEntry(ref: ServerLogIndexEntry): ServerLogEntry =
        mutex.withLock {
            ensureInitializedLocked()
            val data = ServerLogSegmentFiles.dataFile(directory, ref.segmentSeq)
            val end = ref.dataOffset.toLong() + ref.toolNameLen + ref.messageLen
            if (!data.isFile || end > data.length()) {
                return@withLock ServerLogEntry(ref.timestamp, ref.type, CORRUPTED_ENTRY_MESSAGE)
            }
            RandomAccessFile(data, "r").use { raf ->
                raf.seek(ref.dataOffset.toLong())
                val toolNameBytes = ByteArray(ref.toolNameLen).also { raf.readFully(it) }
                val messageBytes = ByteArray(ref.messageLen).also { raf.readFully(it) }
                ServerLogEntry(
                    timestamp = ref.timestamp,
                    type = ref.type,
                    message = String(messageBytes, Charsets.UTF_8),
                    toolName = String(toolNameBytes, Charsets.UTF_8).ifEmpty { null },
                    durationMs = ref.durationMs,
                )
            }
        }

    suspend fun clear(): Unit =
        mutex.withLock {
            ensureInitializedLocked()
            closeStreamsLocked()
            segmentSeqsLocked().forEach { deletePairLocked(it) }
            activeSeq += 1
            activeCount = 0
            activeDataLength = 0
            openStreamsLocked()
        }

    private fun ensureInitializedLocked() {
        if (initialized) return
        directory.mkdirs()
        segmentSeqsLocked().dropLast(maxSegments).forEach { deletePairLocked(it) }
        activeSeq = segmentSeqsLocked().lastOrNull() ?: 1
        activeCount = ServerLogSegmentFiles.indexRecordCount(ServerLogSegmentFiles.indexFile(directory, activeSeq))
        activeDataLength = ServerLogSegmentFiles.dataFile(directory, activeSeq).length().toInt()
        openStreamsLocked()
        initialized = true
    }

    private fun rollLocked(): List<Int> {
        closeStreamsLocked()
        activeSeq += 1
        activeCount = 0
        activeDataLength = 0
        val existing = segmentSeqsLocked().filter { it != activeSeq }
        val excess = (existing.size - (maxSegments - 1)).coerceAtLeast(0)
        val removed = existing.take(excess)
        removed.forEach { deletePairLocked(it) }
        openStreamsLocked()
        return removed
    }

    private fun openStreamsLocked() {
        dataOut = FileOutputStream(ServerLogSegmentFiles.dataFile(directory, activeSeq), true)
        idxOut = FileOutputStream(ServerLogSegmentFiles.indexFile(directory, activeSeq), true)
    }

    private fun closeStreamsLocked() {
        dataOut?.close()
        dataOut = null
        idxOut?.close()
        idxOut = null
    }

    private fun deletePairLocked(seq: Int) {
        ServerLogSegmentFiles.indexFile(directory, seq).delete()
        ServerLogSegmentFiles.dataFile(directory, seq).delete()
    }

    private fun segmentSeqsLocked(): List<Int> = ServerLogSegmentFiles.segmentSeqs(directory)

    companion object {
        const val MAX_ENTRIES_PER_SEGMENT = 1000
        const val MAX_SEGMENTS = 20
        const val MAX_ENTRY_DATA_BYTES = 500
        const val MAX_TOOL_NAME_BYTES = 100
        const val CORRUPTED_ENTRY_MESSAGE = "(corrupted log entry)"
    }
}

/**
 * Pure file-format helpers for [ServerLogSegmentedStore]: segment file naming, index-record
 * parsing, and UTF-8 truncation. Split out so both classes stay within detekt's
 * TooManyFunctions cap without suppression; these functions are stateless and lock-free —
 * the store serializes all calls under its mutex.
 */
internal object ServerLogSegmentFiles {
    const val INDEX_RECORD_BYTES = 24
    const val NO_DURATION = -1
    private const val SEGMENT_NAME_FORMAT = "%05d"
    private const val IDX_SUFFIX = ".idx"
    private const val DATA_SUFFIX = ".data"
    private const val MAX_UNSIGNED_SHORT = 0xFFFF
    private const val CONTINUATION_MASK = 0xC0
    private const val CONTINUATION_MARKER = 0x80

    fun indexFile(
        directory: File,
        seq: Int,
    ): File = File(directory, SEGMENT_NAME_FORMAT.format(seq) + IDX_SUFFIX)

    fun dataFile(
        directory: File,
        seq: Int,
    ): File = File(directory, SEGMENT_NAME_FORMAT.format(seq) + DATA_SUFFIX)

    fun segmentSeqs(directory: File): List<Int> =
        directory
            .listFiles { file -> file.name.endsWith(IDX_SUFFIX) }
            ?.mapNotNull { it.name.removeSuffix(IDX_SUFFIX).toIntOrNull() }
            ?.sorted()
            .orEmpty()

    /** Integer division silently ignores a truncated partial tail record (crash mid-write). */
    fun indexRecordCount(file: File): Int = if (file.isFile) (file.length() / INDEX_RECORD_BYTES).toInt() else 0

    fun readSegmentIndex(
        directory: File,
        seq: Int,
    ): List<ServerLogIndexEntry> {
        val idx = indexFile(directory, seq)
        val recordCount = indexRecordCount(idx)
        if (recordCount == 0) return emptyList()
        val entries = ArrayList<ServerLogIndexEntry>(recordCount)
        RandomAccessFile(idx, "r").use { raf ->
            val mappedSize = recordCount.toLong() * INDEX_RECORD_BYTES
            val buffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, mappedSize)
            for (slot in 0 until recordCount) {
                buffer.position(slot * INDEX_RECORD_BYTES)
                val timestamp = buffer.long
                val dataOffset = buffer.int
                val duration = buffer.int
                val toolNameLen = buffer.short.toInt() and MAX_UNSIGNED_SHORT
                val messageLen = buffer.short.toInt() and MAX_UNSIGNED_SHORT
                val type = ServerLogEntry.Type.fromId(buffer.get()) ?: continue
                entries.add(
                    ServerLogIndexEntry(
                        segmentSeq = seq,
                        slot = slot,
                        timestamp = timestamp,
                        type = type,
                        durationMs = if (duration == NO_DURATION) null else duration.toLong(),
                        dataOffset = dataOffset,
                        toolNameLen = toolNameLen,
                        messageLen = messageLen,
                    ),
                )
            }
        }
        return entries
    }

    /** Truncates to at most [maxBytes] UTF-8 bytes without splitting a multi-byte sequence. */
    fun truncateUtf8(
        value: String,
        maxBytes: Int,
    ): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        var end = maxBytes
        while (end > 0 && (bytes[end].toInt() and CONTINUATION_MASK) == CONTINUATION_MARKER) {
            end--
        }
        return bytes.copyOf(end)
    }
}
