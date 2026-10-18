package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.di.OAuthClientsDataStore
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthClient
import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [OAuthClientRepository] backed by a dedicated Preferences DataStore (`oauth_clients`). The full list
 * is kept as an in-memory [MutableStateFlow] snapshot (seeded once from disk under a [Mutex]); every
 * mutation is write-through (snapshot + disk). Reads are served from the snapshot — no per-call disk
 * decode on the `/mcp` hot path.
 */
@Singleton
class OAuthClientRepositoryImpl
    @Inject
    constructor(
        @OAuthClientsDataStore private val dataStore: DataStore<Preferences>,
        private val serverLog: ServerLogRepository,
    ) : OAuthClientRepository {
        private val json = Json { ignoreUnknownKeys = true }
        private val snapshot = MutableStateFlow<List<OAuthClient>>(emptyList())
        private val mutex = Mutex()
        private var seeded = false

        /** Loads the persisted list into the snapshot once. MUST be called with [mutex] held. */
        private suspend fun seedLocked() {
            if (seeded) return
            val stored = dataStore.data.first()[OAUTH_CLIENTS_KEY]
            snapshot.value = parse(stored)
            seeded = true
        }

        private fun parse(stored: String?): List<OAuthClient> {
            if (stored.isNullOrEmpty()) return emptyList()
            return runCatching { json.decodeFromString<List<OAuthClient>>(stored) }.getOrDefault(emptyList())
        }

        /** Persists [list] to the snapshot and disk. MUST be called with [mutex] held. */
        private suspend fun persist(list: List<OAuthClient>) {
            snapshot.value = list
            dataStore.edit { it[OAUTH_CLIENTS_KEY] = json.encodeToString(list) }
        }

        override fun observeClients(): Flow<List<OAuthClient>> =
            flow {
                mutex.withLock { seedLocked() }
                emitAll(snapshot)
            }

        override suspend fun getClients(): List<OAuthClient> =
            mutex.withLock {
                seedLocked()
                snapshot.value
            }

        override suspend fun getClient(clientId: String): OAuthClient? =
            mutex.withLock {
                seedLocked()
                snapshot.value.firstOrNull { it.clientId == clientId }
            }

        override suspend fun register(
            clientName: String?,
            redirectUris: List<String>,
            applicationType: String?,
            logoUri: String?,
            nowMs: Long,
        ): OAuthClient =
            mutex.withLock {
                seedLocked()
                val newClient =
                    OAuthClient(
                        clientId = "arc-${UUID.randomUUID()}",
                        clientName = clientName,
                        redirectUris = redirectUris,
                        applicationType = applicationType,
                        logoUri = logoUri,
                        createdAtMs = nowMs,
                        lastUsedAtMs = nowMs,
                        currentRefreshJti = null,
                    )
                val existing = snapshot.value
                val combined = existing + newClient
                val finalList =
                    if (combined.size > OAuthPolicy.MAX_OAUTH_CLIENTS) {
                        // Choose the victim among EXISTING clients only (the newcomer is always kept).
                        // Prefer the oldest unapproved (never completed a token grant); fall back to the
                        // overall oldest only when every existing client is approved — so registration
                        // spam (which adds unapproved entries) can never evict an approved client.
                        val unapprovedExisting = existing.filter { it.currentRefreshJti == null }
                        val victim =
                            (unapprovedExisting.ifEmpty { existing }).minByOrNull { it.lastUsedAtMs }
                        if (victim != null) combined - victim else combined
                    } else {
                        combined
                    }
                persist(finalList)
                newClient
            }

        override suspend fun revoke(clientId: String) {
            mutex.withLock {
                seedLocked()
                val removed = snapshot.value.firstOrNull { it.clientId == clientId }
                val updated = snapshot.value.filterNot { it.clientId == clientId }
                if (updated.size != snapshot.value.size) {
                    persist(updated)
                    serverLog.log(
                        ServerLogEntry.Type.OAUTH,
                        "OAuth client revoked: ${removed?.clientName ?: clientId}",
                    )
                }
            }
        }

        override suspend fun touchLastUsed(
            clientId: String,
            nowMs: Long,
        ) {
            mutex.withLock {
                seedLocked()
                var changed = false
                val updated =
                    snapshot.value.map {
                        if (it.clientId == clientId) {
                            changed = true
                            it.copy(lastUsedAtMs = nowMs)
                        } else {
                            it
                        }
                    }
                if (changed) persist(updated)
            }
        }

        override suspend fun setRefreshJti(
            clientId: String,
            jti: String,
        ) {
            mutex.withLock {
                seedLocked()
                var changed = false
                val updated =
                    snapshot.value.map {
                        if (it.clientId == clientId) {
                            changed = true
                            it.copy(currentRefreshJti = jti)
                        } else {
                            it
                        }
                    }
                if (changed) persist(updated)
            }
        }

        private companion object {
            private val OAUTH_CLIENTS_KEY = stringPreferencesKey("oauth_clients")
        }
    }
