package com.danielealbano.androidremotecontrolmcp.data.repository

import android.content.Context
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Disk-backed [ServerLogRepository]. [log] enqueues to an unbounded channel drained by a single
 * writer coroutine (writes are strictly ordered; callers never block). The full index is held in
 * an in-memory cache — loaded once from the store's memory-mapped read, appended incrementally
 * per write, pruned on rotation, reset on [clear] — so readers never rescan the segment files.
 * Messages and tool names pass through [Logger.sanitize] as a backstop (it masks UUID-format
 * tokens only); non-UUID secrets never reach [log] because the emitters exclude them by
 * construction (settings render lambdas never output secret values).
 */
@Singleton
class ServerLogRepositoryImpl internal constructor(
    private val store: ServerLogSegmentedStore,
    private val ioDispatcher: CoroutineDispatcher,
) : ServerLogRepository {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(ServerLogSegmentedStore(File(context.filesDir, LOG_DIRECTORY_NAME)), ioDispatcher)

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val queue = Channel<ServerLogEntry>(Channel.UNLIMITED)
    private val cacheMutex = Mutex()
    private var indexCache: MutableList<ServerLogIndexEntry>? = null

    private val _revision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = _revision.asStateFlow()

    init {
        scope.launch {
            for (entry in queue) {
                try {
                    cacheMutex.withLock {
                        val result =
                            store.append(entry.timestamp, entry.type, entry.message, entry.toolName, entry.durationMs)
                        indexCache?.let { cache ->
                            result.removedSegmentSeqs.forEach { seq ->
                                cache.removeAll { ref -> ref.segmentSeq == seq }
                            }
                            cache.add(result.entry)
                        }
                    }
                    _revision.update { it + 1 }
                } catch (e: IOException) {
                    Log.w(TAG, "Failed to persist server log entry", e)
                }
            }
        }
    }

    override fun log(
        type: ServerLogEntry.Type,
        message: String,
        toolName: String?,
        durationMs: Long?,
    ) {
        queue.trySend(
            ServerLogEntry(
                timestamp = System.currentTimeMillis(),
                type = type,
                message = Logger.sanitize(message),
                toolName = toolName?.let(Logger::sanitize),
                durationMs = durationMs,
            ),
        )
    }

    override suspend fun readIndex(): List<ServerLogIndexEntry> =
        withContext(ioDispatcher) { cacheMutex.withLock { ensureCacheLocked().toList() } }

    override suspend fun readEntry(ref: ServerLogIndexEntry) = withContext(ioDispatcher) { store.readEntry(ref) }

    override suspend fun recent(count: Int): List<ServerLogEntry> =
        withContext(ioDispatcher) {
            val refs = cacheMutex.withLock { ensureCacheLocked().takeLast(count) }
            refs.asReversed().map { store.readEntry(it) }
        }

    override suspend fun clear() {
        withContext(ioDispatcher) {
            cacheMutex.withLock {
                store.clear()
                indexCache = mutableListOf()
            }
        }
        _revision.update { it + 1 }
    }

    /** Loads the cache from disk on first use. Caller MUST hold [cacheMutex]. */
    private suspend fun ensureCacheLocked(): MutableList<ServerLogIndexEntry> =
        indexCache ?: store.readIndex().toMutableList().also { indexCache = it }

    companion object {
        const val LOG_DIRECTORY_NAME = "server_logs"
        private const val TAG = "MCP:ServerLogRepo"
    }
}
