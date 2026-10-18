package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogIndexEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** Access-ordered LRU map bounded at [maxSize], keyed by [ServerLogIndexEntry.cacheKey]. */
private class LruEntryCache(
    private val maxSize: Int,
) : LinkedHashMap<String, ServerLogEntry>(maxSize, LOAD_FACTOR, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ServerLogEntry>?) = size > maxSize

    private companion object {
        private const val LOAD_FACTOR = 0.75f
    }
}

@HiltViewModel
class LogsViewModel
    @Inject
    constructor(
        private val serverLogRepository: ServerLogRepository,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _selectedTypes = MutableStateFlow(ServerLogEntry.Type.entries.toSet())
        val selectedTypes: StateFlow<Set<ServerLogEntry.Type>> = _selectedTypes.asStateFlow()

        /**
         * Newest entries for the Server screen's recent card, newest first. The `revision`
         * StateFlow already conflates, and a trailing pacing delay bounds the recompute rate under
         * write bursts (the latest revision is always processed eventually; no FlowPreview API needed).
         */
        val recentServerLogs: StateFlow<List<ServerLogEntry>> =
            serverLogRepository.revision
                .transform {
                    emit(serverLogRepository.recent(RECENT_LOG_COUNT))
                    delay(REFRESH_THROTTLE_MS)
                }.flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), emptyList())

        /** Filtered index, newest first, from the repository's in-memory cache — same throttling. */
        val filteredIndex: StateFlow<List<ServerLogIndexEntry>> =
            combine(serverLogRepository.revision, _selectedTypes) { _, types -> types }
                .conflate()
                .transform { types ->
                    emit(serverLogRepository.readIndex().filter { it.type in types }.asReversed())
                    delay(REFRESH_THROTTLE_MS)
                }.flowOn(ioDispatcher)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), emptyList())

        private val cacheMutex = Mutex()
        private val entryCache = LruEntryCache(CACHE_SIZE)

        fun toggleType(type: ServerLogEntry.Type) {
            _selectedTypes.update { current ->
                if (type in current) current - type else current + type
            }
        }

        /** Materializes one entry, LRU-cached by [ServerLogIndexEntry.cacheKey]. */
        suspend fun entryAt(ref: ServerLogIndexEntry): ServerLogEntry {
            cacheMutex.withLock { entryCache[ref.cacheKey] }?.let { return it }
            val entry = serverLogRepository.readEntry(ref)
            cacheMutex.withLock { entryCache[ref.cacheKey] = entry }
            return entry
        }

        fun clearLogs() {
            viewModelScope.launch(ioDispatcher) {
                serverLogRepository.clear()
                cacheMutex.withLock { entryCache.clear() }
            }
        }

        companion object {
            private const val FLOW_TIMEOUT_MS = 5_000L
            private const val CACHE_SIZE = 200
            private const val RECENT_LOG_COUNT = 5
            private const val REFRESH_THROTTLE_MS = 250L
        }
    }
