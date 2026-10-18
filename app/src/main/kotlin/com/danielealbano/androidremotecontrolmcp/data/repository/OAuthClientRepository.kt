package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.mcp.oauth.OAuthClient
import kotlinx.coroutines.flow.Flow

/**
 * Persisted, revocable registry of DCR-registered OAuth clients. Backed by a DEDICATED Preferences
 * DataStore (not the shared settings store). An in-memory snapshot serves `/mcp` hot-path lookups so
 * no per-request JSON decode from disk is needed.
 */
interface OAuthClientRepository {
    /** Observes the current client list (in-memory snapshot, write-through to disk). */
    fun observeClients(): Flow<List<OAuthClient>>

    /** Returns the current client list. */
    suspend fun getClients(): List<OAuthClient>

    /** Returns the client with [clientId], or null. */
    suspend fun getClient(clientId: String): OAuthClient?

    /**
     * Registers a new client and returns it. Enforces the cap by evicting the oldest-by-last-used
     * client WITHOUT a refresh jti (never completed a token grant); only when every client has one
     * does it evict the overall oldest — so registration spam cannot evict an approved client.
     */
    suspend fun register(
        clientName: String?,
        redirectUris: List<String>,
        applicationType: String?,
        logoUri: String?,
        nowMs: Long,
    ): OAuthClient

    /** Removes the client (its tokens stop validating immediately). */
    suspend fun revoke(clientId: String)

    /** Updates the client's last-used timestamp. */
    suspend fun touchLastUsed(
        clientId: String,
        nowMs: Long,
    )

    /** Sets the client's current refresh-token jti (rotation). */
    suspend fun setRefreshJti(
        clientId: String,
        jti: String,
    )
}
