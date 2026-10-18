package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import kotlinx.coroutines.flow.StateFlow

/**
 * Lightweight reference to one stored log entry: everything needed to render a list row except the
 * variable-length strings, plus the coordinates ([segmentSeq], [slot], [dataOffset]) to load them.
 */
data class ServerLogIndexEntry(
    val segmentSeq: Int,
    val slot: Int,
    val timestamp: Long,
    val type: ServerLogEntry.Type,
    val durationMs: Long?,
    val dataOffset: Int,
    val toolNameLen: Int,
    val messageLen: Int,
) {
    /** Stable identity for list keys and caches (segment sequences are never reused in-process). */
    val cacheKey: String get() = "$segmentSeq:$slot:$timestamp"
}

/**
 * Process-wide sink and reader for the server log shown in the in-app logs viewer.
 * Writes are non-blocking ([log] enqueues to a single writer); reads are suspend and IO-bound.
 */
interface ServerLogRepository {
    /** Bumped after every persisted write and after [clear]; collect to refresh views. */
    val revision: StateFlow<Long>

    /** Enqueues an entry. Never blocks; message and toolName are sanitized before persisting. */
    fun log(
        type: ServerLogEntry.Type,
        message: String,
        toolName: String? = null,
        durationMs: Long? = null,
    )

    /** Full index, oldest → newest (global order across segments). */
    suspend fun readIndex(): List<ServerLogIndexEntry>

    /** Materializes one entry (loads toolName/message bytes from disk). */
    suspend fun readEntry(ref: ServerLogIndexEntry): ServerLogEntry

    /** The [count] most recent entries, newest first, fully materialized. */
    suspend fun recent(count: Int): List<ServerLogEntry>

    /** Deletes all persisted entries. */
    suspend fun clear()
}
