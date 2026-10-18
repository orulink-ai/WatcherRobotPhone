package com.danielealbano.androidremotecontrolmcp.mcp.oauth

import java.security.MessageDigest
import java.util.Base64

/**
 * PKCE (RFC 7636) S256 verification. Only the S256 method is supported (Claude uses S256); a `plain`
 * verifier is not accepted by these helpers.
 */
object Pkce {
    /**
     * Returns true iff `base64url(sha256(verifier)) == challenge`. Blank inputs return false. The final
     * comparison is constant-time ([MessageDigest.isEqual]) for parity with the project's
     * `constantTimeEquals` convention.
     */
    fun verifyS256(
        verifier: String,
        challenge: String,
    ): Boolean {
        if (verifier.isBlank() || challenge.isBlank()) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        val computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        return MessageDigest.isEqual(computed.toByteArray(Charsets.US_ASCII), challenge.toByteArray(Charsets.US_ASCII))
    }
}
