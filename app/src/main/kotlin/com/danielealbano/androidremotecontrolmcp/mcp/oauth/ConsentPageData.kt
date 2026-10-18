package com.danielealbano.androidremotecontrolmcp.mcp.oauth

/**
 * View model for the [consentPageHtml]: the requesting client's identity and the live approval window.
 *
 * @property iconUrl an SSRF-safe logo or public-host favicon resolved by [ClientIconUrl] (rendered over a
 *   CSS monogram fallback) — the only external reference, and only for public hosts, matching the in-app
 *   screen.
 */
data class ConsentPageData(
    val approvalId: String,
    val matchCode: String,
    val clientName: String,
    val host: String,
    val expiresInSeconds: Long,
    val iconUrl: String?,
)
