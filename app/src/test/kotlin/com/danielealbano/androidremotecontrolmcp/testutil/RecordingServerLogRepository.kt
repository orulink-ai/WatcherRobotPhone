package com.danielealbano.androidremotecontrolmcp.testutil

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogIndexEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory [ServerLogRepository] recording every entry synchronously for assertions. */
class RecordingServerLogRepository : ServerLogRepository {
    val entries = CopyOnWriteArrayList<ServerLogEntry>()

    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision.asStateFlow()

    override fun log(
        type: ServerLogEntry.Type,
        message: String,
        toolName: String?,
        durationMs: Long?,
    ) {
        entries.add(
            ServerLogEntry(
                timestamp = entries.size.toLong(),
                type = type,
                message = message,
                toolName = toolName,
                durationMs = durationMs,
            ),
        )
        _revision.update { it + 1 }
    }

    override suspend fun readIndex(): List<ServerLogIndexEntry> =
        entries.mapIndexed { slot, entry ->
            ServerLogIndexEntry(
                segmentSeq = 1,
                slot = slot,
                timestamp = entry.timestamp,
                type = entry.type,
                durationMs = entry.durationMs,
                dataOffset = 0,
                toolNameLen = 0,
                messageLen = 0,
            )
        }

    override suspend fun readEntry(ref: ServerLogIndexEntry): ServerLogEntry = entries[ref.slot]

    override suspend fun recent(count: Int): List<ServerLogEntry> = entries.takeLast(count).reversed()

    override suspend fun clear() {
        entries.clear()
        _revision.update { it + 1 }
    }

    /** Entries of one type, in emission order. */
    fun ofType(type: ServerLogEntry.Type): List<ServerLogEntry> = entries.filter { it.type == type }
}
