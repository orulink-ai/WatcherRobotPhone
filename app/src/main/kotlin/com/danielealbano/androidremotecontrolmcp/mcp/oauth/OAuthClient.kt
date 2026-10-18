package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import kotlinx.serialization.Serializable

/**
 * A persisted DCR (RFC 7591) client registration.
 *
 * @property clientId Server-issued identifier (`arc-<uuid>`).
 * @property clientName DCR `client_name` (e.g. "Claude"); may be absent.
 * @property redirectUris Registered redirect URIs (allowlist-validated at registration).
 * @property applicationType DCR `application_type` (e.g. "web"); shown in the clients row.
 * @property logoUri DCR `logo_uri`; rendered (SSRF-guarded) in the clients row, else an initials avatar.
 * @property createdAtMs Registration timestamp.
 * @property lastUsedAtMs Last `/mcp` use (debounced) or token grant.
 * @property currentRefreshJti The single valid refresh-token jti; null until the first token grant.
 *   Used both for refresh rotation and to protect already-approved clients from registration-spam eviction.
 */
@Serializable
data class OAuthClient(
    val clientId: String,
    val clientName: String? = null,
    val redirectUris: List<String>,
    val applicationType: String? = null,
    val logoUri: String? = null,
    val createdAtMs: Long,
    val lastUsedAtMs: Long,
    val currentRefreshJti: String? = null,
)
